package org.jawata.mcp.embed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A reader for the {@code safetensors} weight format.
 *
 * <p>We read the UPSTREAM checkpoint file as published rather than converting
 * it to a private format. That is a provenance decision: the bundled file's
 * sha256 matches the one recorded in the dossier and on the model hub, so
 * "these are the published weights" is verifiable by anyone, not a claim to be
 * taken on trust. A converted blob would be unverifiable without re-running our
 * own converter.</p>
 *
 * <p>The format is deliberately simple: an 8-byte little-endian header length,
 * that many bytes of JSON naming each tensor with its dtype, shape and byte
 * range, then the raw tensor data back to back.</p>
 *
 * <p>Both {@code F32} and {@code F16} are supported, so the same reader loads
 * the full-precision and half-precision variants and the precision choice costs
 * no code. Everything is widened to {@code float} on read — half precision is a
 * STORAGE decision, never an arithmetic one.</p>
 */
public final class SafeTensors {

    private static final Set<String> SUPPORTED = Set.of("F32", "F16");

    private final Map<String, float[]> tensors;
    private final Map<String, int[]> shapes;

    private SafeTensors(Map<String, float[]> tensors, Map<String, int[]> shapes) {
        this.tensors = tensors;
        this.shapes = shapes;
    }

    /**
     * Read a whole safetensors stream into memory.
     *
     * <p>Non-float tensors (the checkpoint carries an {@code I64}
     * {@code position_ids} buffer) are SKIPPED rather than rejected: they are
     * not weights, and refusing the file over one unused bookkeeping tensor
     * would be pedantry. Anything else unrecognised IS rejected — silently
     * ignoring a tensor we cannot read would produce a model that is quietly
     * wrong.</p>
     */
    public static SafeTensors read(InputStream in) {
        try {
            byte[] lenBytes = in.readNBytes(8);
            if (lenBytes.length < 8) {
                throw new IllegalArgumentException("not a safetensors stream: truncated header length");
            }
            long headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (headerLen <= 0 || headerLen > 100_000_000L) {
                throw new IllegalArgumentException("implausible safetensors header length: " + headerLen);
            }
            byte[] headerBytes = in.readNBytes((int) headerLen);
            JsonNode header = new ObjectMapper()
                .readTree(new String(headerBytes, StandardCharsets.UTF_8));

            byte[] body = in.readAllBytes();
            ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);

            Map<String, float[]> tensors = new HashMap<>();
            Map<String, int[]> shapes = new HashMap<>();
            var fields = header.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                String name = e.getKey();
                if ("__metadata__".equals(name)) {
                    continue;
                }
                JsonNode t = e.getValue();
                String dtype = t.path("dtype").asText();
                long start = t.path("data_offsets").get(0).asLong();
                long end = t.path("data_offsets").get(1).asLong();
                if (!SUPPORTED.contains(dtype)) {
                    if ("I64".equals(dtype)) {
                        continue;                  // position_ids bookkeeping, not a weight
                    }
                    throw new IllegalArgumentException(
                        "unsupported tensor dtype " + dtype + " for " + name);
                }
                int[] shape = new int[t.path("shape").size()];
                for (int i = 0; i < shape.length; i++) {
                    shape[i] = t.path("shape").get(i).asInt();
                }
                tensors.put(name, readFloats(buf, (int) start, (int) end, dtype));
                shapes.put(name, shape);
            }
            return new SafeTensors(tensors, shapes);
        } catch (IOException ex) {
            throw new UncheckedIOException("reading safetensors", ex);
        }
    }

    private static float[] readFloats(ByteBuffer buf, int start, int end, String dtype) {
        int bytes = end - start;
        if ("F32".equals(dtype)) {
            float[] out = new float[bytes / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = buf.getFloat(start + i * 4);
            }
            return out;
        }
        float[] out = new float[bytes / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = halfToFloat(buf.getShort(start + i * 2));
        }
        return out;
    }

    /** IEEE-754 binary16 → binary32. Exact: every half value is representable. */
    static float halfToFloat(short half) {
        return Float.float16ToFloat(half);
    }

    /** The named tensor, widened to float.
     *
     *  @throws IllegalArgumentException when absent — a missing weight must
     *      fail loudly at load, never default to zeros and produce a model that
     *      silently returns meaningless vectors. */
    public float[] tensor(String name) {
        float[] t = tensors.get(name);
        if (t == null) {
            throw new IllegalArgumentException("checkpoint has no tensor named " + name);
        }
        return t;
    }

    /** The named tensor's shape. */
    public int[] shape(String name) {
        int[] s = shapes.get(name);
        if (s == null) {
            throw new IllegalArgumentException("checkpoint has no tensor named " + name);
        }
        return s.clone();
    }

    /** Whether a tensor of this name is present in the file. */
    public boolean has(String name) {
        return tensors.containsKey(name);
    }

    /** How many tensors the file declares. */
    public int tensorCount() {
        return tensors.size();
    }
}
