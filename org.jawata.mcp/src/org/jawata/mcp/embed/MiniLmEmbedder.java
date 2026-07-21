package org.jawata.mcp.embed;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Sprint 27 D1 — the owned MiniLM encoder: token ids in, one normalized
 * sentence vector out, computed entirely in this process from bundled weights.
 *
 * <p>No network, no external process, no third-party inference runtime. That is
 * the sprint's whole point: the store's semantic recall must work on a laptop
 * with no connectivity and must never send a line of the user's code or notes
 * anywhere. The parity gate (cosine ≥ 0.999 against reference vectors on the
 * committed golden set) is what makes "owned" mean "identical", not "similar".</p>
 *
 * <p>Architecture, taken from the checkpoint's own config: 6 encoder layers,
 * hidden 384, 12 heads of 32, intermediate 1536, absolute position embeddings,
 * LayerNorm eps 1e-12, exact (erf-based) GELU. Pooling is the MEAN over all
 * token vectors — including [CLS] and [SEP], because sentence-transformers
 * pools under the attention mask and those positions are unmasked — followed by
 * L2 normalization, so cosine similarity is a plain dot product downstream.</p>
 *
 * <p>Linear weights arrive as {@code [out, in]} (the PyTorch convention) and are
 * TRANSPOSED once at load, so the hot path is a plain row-major multiply through
 * {@link MatMul} rather than a strided one. Attention scores are computed
 * directly instead of through the seam: they are tiny next to the projections
 * (tokens² × 32 against tokens × 384 × 1536), so routing them through a backend
 * would add indirection for no measurable gain.</p>
 */
public final class MiniLmEmbedder {

    private static final String F16_RESOURCE = "/embed/model-f16.safetensors";
    private static final String F32_RESOURCE = "/embed/model-f32.safetensors";

    private static final int HIDDEN = 384;
    private static final int LAYERS = 6;
    private static final int HEADS = 12;
    private static final int HEAD_DIM = HIDDEN / HEADS;          // 32
    private static final int INTERMEDIATE = 1536;
    private static final int MAX_POSITIONS = 512;
    /** The window sentence-transformers configures for this checkpoint. */
    public static final int MAX_SEQ_LENGTH = 256;
    private static final float LAYER_NORM_EPS = 1e-12f;

    private final WordPieceTokenizer tokenizer;
    private final MatMul matMul;
    private final String precision;

    private final float[] wordEmbeddings;        // [vocab, 384]
    private final float[] positionEmbeddings;    // [512, 384]
    private final float[] tokenTypeEmbeddings;   // [2, 384]
    private final float[] embedNormWeight;
    private final float[] embedNormBias;
    private final Layer[] layers = new Layer[LAYERS];

    /** One transformer block's weights, all linears pre-transposed to [in, out]. */
    private static final class Layer {
        float[] qw;
        float[] qb;
        float[] kw;
        float[] kb;
        float[] vw;
        float[] vb;
        float[] attnOutW;
        float[] attnOutB;
        float[] attnNormW;
        float[] attnNormB;
        float[] interW;
        float[] interB;
        float[] outW;
        float[] outB;
        float[] outNormW;
        float[] outNormB;
    }

    private MiniLmEmbedder(WordPieceTokenizer tokenizer, SafeTensors w,
                           MatMul matMul, String precision) {
        this.tokenizer = tokenizer;
        this.matMul = matMul;
        this.precision = precision;

        this.wordEmbeddings = w.tensor("embeddings.word_embeddings.weight");
        this.positionEmbeddings = w.tensor("embeddings.position_embeddings.weight");
        this.tokenTypeEmbeddings = w.tensor("embeddings.token_type_embeddings.weight");
        this.embedNormWeight = w.tensor("embeddings.LayerNorm.weight");
        this.embedNormBias = w.tensor("embeddings.LayerNorm.bias");

        for (int i = 0; i < LAYERS; i++) {
            String p = "encoder.layer." + i + ".";
            Layer l = new Layer();
            l.qw = transpose(w.tensor(p + "attention.self.query.weight"), HIDDEN, HIDDEN);
            l.qb = w.tensor(p + "attention.self.query.bias");
            l.kw = transpose(w.tensor(p + "attention.self.key.weight"), HIDDEN, HIDDEN);
            l.kb = w.tensor(p + "attention.self.key.bias");
            l.vw = transpose(w.tensor(p + "attention.self.value.weight"), HIDDEN, HIDDEN);
            l.vb = w.tensor(p + "attention.self.value.bias");
            l.attnOutW = transpose(w.tensor(p + "attention.output.dense.weight"), HIDDEN, HIDDEN);
            l.attnOutB = w.tensor(p + "attention.output.dense.bias");
            l.attnNormW = w.tensor(p + "attention.output.LayerNorm.weight");
            l.attnNormB = w.tensor(p + "attention.output.LayerNorm.bias");
            l.interW = transpose(w.tensor(p + "intermediate.dense.weight"), INTERMEDIATE, HIDDEN);
            l.interB = w.tensor(p + "intermediate.dense.bias");
            l.outW = transpose(w.tensor(p + "output.dense.weight"), HIDDEN, INTERMEDIATE);
            l.outB = w.tensor(p + "output.dense.bias");
            l.outNormW = w.tensor(p + "output.LayerNorm.weight");
            l.outNormB = w.tensor(p + "output.LayerNorm.bias");
            layers[i] = l;
        }
    }

    /**
     * Load the embedder from the weights bundled in this jar, preferring the
     * half-precision file when it is present.
     *
     * <p>Which file ships IS the precision decision — the code needs no flag,
     * and swapping the bundled file swaps the precision. Absence of both is a
     * broken build and fails loudly.</p>
     */
    public static MiniLmEmbedder bundled() {
        return bundled(MatMuls.active());
    }

    /**
     * The bundled embedder on a caller-chosen matrix backend — fp16 first,
     * fp32 if that resource is absent.
     *
     * @param matMul the multiply implementation to use (see {@link MatMuls})
     * @return the embedder, or {@code null} when no weights are bundled
     */
    public static MiniLmEmbedder bundled(MatMul matMul) {
        for (String resource : new String[] {F16_RESOURCE, F32_RESOURCE}) {
            MiniLmEmbedder e = fromResource(resource, matMul);
            if (e != null) {
                return e;
            }
        }
        throw new IllegalStateException(
            "no bundled model weights found on the classpath (looked for "
            + F16_RESOURCE + " and " + F32_RESOURCE + ")");
    }

    /**
     * Load from one specific weight resource, or {@code null} when it is not on
     * the classpath. Package-private so the C2 parity gate can measure BOTH
     * precisions in one run and let the measurement pick the shipped one,
     * rather than the choice being asserted.
     */
    static MiniLmEmbedder fromResource(String resource, MatMul matMul) {
        InputStream raw = MiniLmEmbedder.class.getResourceAsStream(resource);
        if (raw == null) {
            return null;
        }
        try (InputStream in = new BufferedInputStream(raw, 1 << 20)) {
            return new MiniLmEmbedder(WordPieceTokenizer.bundled(), SafeTensors.read(in),
                matMul, resource.contains("f16") ? "fp16" : "fp32");
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + resource, e);
        }
    }

    static final String RESOURCE_F16 = F16_RESOURCE;
    static final String RESOURCE_F32 = F32_RESOURCE;

    /** "fp16" or "fp32" — which weight file this instance actually loaded. */
    public String precision() {
        return precision;
    }

    /** The backend doing the arithmetic, for honest capability reporting. */
    public String backend() {
        return matMul.name();
    }

    /** What this embedder's vectors may be compared against. */
    public EmbedderIdentity identity() {
        return EmbedderIdentity.current();
    }

    /** Embed one text to a unit-length 384-vector. */
    public float[] embed(String text) {
        int[] ids = tokenizer.encode(text, MAX_SEQ_LENGTH);
        int t = ids.length;

        // --- embeddings: word + position + token-type(0), then LayerNorm ---
        float[] x = new float[t * HIDDEN];
        for (int i = 0; i < t; i++) {
            int wordOff = ids[i] * HIDDEN;
            int posOff = Math.min(i, MAX_POSITIONS - 1) * HIDDEN;
            int rowOff = i * HIDDEN;
            for (int h = 0; h < HIDDEN; h++) {
                x[rowOff + h] = wordEmbeddings[wordOff + h]
                    + positionEmbeddings[posOff + h]
                    + tokenTypeEmbeddings[h];              // segment 0
            }
        }
        layerNorm(x, t, embedNormWeight, embedNormBias);

        for (Layer l : layers) {
            x = block(x, t, l);
        }
        return normalize(meanPool(x, t));
    }

    // --- one transformer block ------------------------------------------------

    private float[] block(float[] x, int t, Layer l) {
        float[] q = linear(x, t, HIDDEN, l.qw, l.qb, HIDDEN);
        float[] k = linear(x, t, HIDDEN, l.kw, l.kb, HIDDEN);
        float[] v = linear(x, t, HIDDEN, l.vw, l.vb, HIDDEN);

        float[] ctx = attention(q, k, v, t);
        float[] attnOut = linear(ctx, t, HIDDEN, l.attnOutW, l.attnOutB, HIDDEN);
        addInPlace(attnOut, x, t * HIDDEN);                    // residual
        layerNorm(attnOut, t, l.attnNormW, l.attnNormB);

        float[] inter = linear(attnOut, t, HIDDEN, l.interW, l.interB, INTERMEDIATE);
        gelu(inter);
        float[] out = linear(inter, t, INTERMEDIATE, l.outW, l.outB, HIDDEN);
        addInPlace(out, attnOut, t * HIDDEN);                  // residual
        layerNorm(out, t, l.outNormW, l.outNormB);
        return out;
    }

    /**
     * Multi-head scaled dot-product attention. No mask: a single sequence is
     * encoded without padding, so every position attends to every other —
     * which is also what the reference computes for an all-ones mask.
     */
    private float[] attention(float[] q, float[] k, float[] v, int t) {
        float[] ctx = new float[t * HIDDEN];
        float scale = (float) (1.0 / Math.sqrt(HEAD_DIM));
        float[] scores = new float[t];
        for (int h = 0; h < HEADS; h++) {
            int base = h * HEAD_DIM;
            for (int i = 0; i < t; i++) {
                float max = Float.NEGATIVE_INFINITY;
                for (int j = 0; j < t; j++) {
                    float dot = 0f;
                    for (int d = 0; d < HEAD_DIM; d++) {
                        dot += q[i * HIDDEN + base + d] * k[j * HIDDEN + base + d];
                    }
                    scores[j] = dot * scale;
                    if (scores[j] > max) {
                        max = scores[j];
                    }
                }
                // softmax, max-shifted for numerical stability
                float sum = 0f;
                for (int j = 0; j < t; j++) {
                    scores[j] = (float) Math.exp(scores[j] - max);
                    sum += scores[j];
                }
                float inv = 1f / sum;
                for (int j = 0; j < t; j++) {
                    float p = scores[j] * inv;
                    if (p == 0f) {
                        continue;
                    }
                    for (int d = 0; d < HEAD_DIM; d++) {
                        ctx[i * HIDDEN + base + d] += p * v[j * HIDDEN + base + d];
                    }
                }
            }
        }
        return ctx;
    }

    // --- primitives -----------------------------------------------------------

    /** {@code y = x·W + b}, with W already transposed to [in, out]. */
    private float[] linear(float[] x, int t, int in, float[] w, float[] b, int out) {
        float[] y = new float[t * out];
        matMul.multiply(x, w, y, t, in, out);
        for (int i = 0; i < t; i++) {
            int row = i * out;
            for (int o = 0; o < out; o++) {
                y[row + o] += b[o];
            }
        }
        return y;
    }

    /** [out, in] → [in, out], done once at load so the hot path stays contiguous. */
    private static float[] transpose(float[] m, int rows, int cols) {
        float[] out = new float[rows * cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out[c * rows + r] = m[r * cols + c];
            }
        }
        return out;
    }

    /** Per-token LayerNorm over the hidden dimension, in place. */
    private static void layerNorm(float[] x, int t, float[] weight, float[] bias) {
        for (int i = 0; i < t; i++) {
            int row = i * HIDDEN;
            float mean = 0f;
            for (int h = 0; h < HIDDEN; h++) {
                mean += x[row + h];
            }
            mean /= HIDDEN;
            float var = 0f;
            for (int h = 0; h < HIDDEN; h++) {
                float d = x[row + h] - mean;
                var += d * d;
            }
            var /= HIDDEN;                       // biased, as PyTorch computes it
            float inv = (float) (1.0 / Math.sqrt(var + LAYER_NORM_EPS));
            for (int h = 0; h < HIDDEN; h++) {
                x[row + h] = (x[row + h] - mean) * inv * weight[h] + bias[h];
            }
        }
    }

    /**
     * Exact GELU, in place: {@code x·0.5·(1 + erf(x/√2))}.
     *
     * <p>The checkpoint declares {@code hidden_act: "gelu"}, which is the
     * erf form, NOT the tanh approximation — they differ by up to ~1e-3, which
     * is far too much for a 0.999 cosine gate.</p>
     */
    private static void gelu(float[] x) {
        for (int i = 0; i < x.length; i++) {
            double v = x[i];
            x[i] = (float) (0.5 * v * (1.0 + erf(v / Math.sqrt(2.0))));
        }
    }

    /** Abramowitz &amp; Stegun 7.1.26 — max absolute error 1.5e-7, below fp32 epsilon. */
    private static double erf(double x) {
        double sign = Math.signum(x);
        double a = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * a);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
            - 0.284496736) * t + 0.254829592) * t * Math.exp(-a * a);
        return sign * y;
    }

    private static void addInPlace(float[] target, float[] addend, int n) {
        for (int i = 0; i < n; i++) {
            target[i] += addend[i];
        }
    }

    /** Mean over all token vectors (the mask is all-ones for a single sequence). */
    private static float[] meanPool(float[] x, int t) {
        float[] pooled = new float[HIDDEN];
        for (int i = 0; i < t; i++) {
            int row = i * HIDDEN;
            for (int h = 0; h < HIDDEN; h++) {
                pooled[h] += x[row + h];
            }
        }
        float inv = 1f / t;
        for (int h = 0; h < HIDDEN; h++) {
            pooled[h] *= inv;
        }
        return pooled;
    }

    /** L2-normalize, so downstream cosine is a plain dot product. */
    private static float[] normalize(float[] v) {
        double sum = 0;
        for (float f : v) {
            sum += (double) f * f;
        }
        float inv = (float) (1.0 / Math.max(Math.sqrt(sum), 1e-12));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
        return v;
    }
}
