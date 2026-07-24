import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Extracts only the tiny frontend/tokenizer data that is not present in the LiteRT export. */
public final class GgufParakeetAssetsExtractor {
    private record TensorInfo(long[] shape, int type, long offset) {}

    public static void main(String[] args) throws Exception {
        try (RandomAccessFile in = new RandomAccessFile(args[0], "r")) {
            require(readU32(in) == 0x46554747L, "Not a GGUF file");
            long version = readU32(in);
            require(version == 2 || version == 3, "Unsupported GGUF version " + version);
            long tensorCount = readU64(in);
            long metadataCount = readU64(in);
            int alignment = 32;
            List<String> vocab = null;

            for (long i = 0; i < metadataCount; i++) {
                String key = readString(in);
                int type = (int) readU32(in);
                if (key.equals("general.alignment") && type == 4) {
                    alignment = (int) readU32(in);
                } else if (key.equals("tokenizer.ggml.tokens") && type == 9) {
                    int elementType = (int) readU32(in);
                    require(elementType == 8, "Tokenizer array is not strings");
                    long count = readU64(in);
                    vocab = new ArrayList<>((int) count);
                    for (long j = 0; j < count; j++) vocab.add(readString(in));
                } else {
                    skipValue(in, type);
                }
            }
            require(vocab != null && vocab.size() == 8192, "Expected 8192 Parakeet tokens");

            Map<String, TensorInfo> tensors = new HashMap<>();
            for (long i = 0; i < tensorCount; i++) {
                String name = readString(in);
                int dims = (int) readU32(in);
                long[] shape = new long[dims];
                for (int d = 0; d < dims; d++) shape[d] = readU64(in);
                int type = (int) readU32(in);
                long offset = readU64(in);
                tensors.put(name, new TensorInfo(shape, type, offset));
            }
            long dataStart = align(in.getFilePointer(), alignment);
            float[] window = readFloatTensor(in, dataStart, tensors.get("preprocessor.window"));
            float[] filterbank = readFloatTensor(in, dataStart, tensors.get("preprocessor.fb"));
            require(window.length == 400, "Unexpected Hann window size " + window.length);
            require(filterbank.length == 128 * 257, "Unexpected mel filterbank size " + filterbank.length);

            Path output = Path.of(args[1]);
            Files.createDirectories(output.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(output))) {
                out.writeInt(0x504B4153); // PKAS
                out.writeInt(vocab.size());
                for (String token : vocab) {
                    byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
                out.writeInt(window.length);
                for (float value : window) out.writeFloat(value);
                out.writeInt(filterbank.length);
                for (float value : filterbank) out.writeFloat(value);
            }
            for (int i = 0; i < Math.min(vocab.size(), 300); i++) {
                System.out.printf("%d\t%s%n", i, vocab.get(i).replace("\n", "\\n"));
            }
            System.out.printf("Wrote %s (%d bytes)%n", output, Files.size(output));
        }
    }

    private static float[] readFloatTensor(RandomAccessFile in, long dataStart, TensorInfo tensor)
            throws IOException {
        require(tensor != null, "Required preprocessing tensor is missing");
        require(tensor.type == 0, "Required preprocessing tensor is not F32");
        long count = 1;
        for (long dim : tensor.shape) count *= dim;
        require(count <= Integer.MAX_VALUE, "Tensor is too large");
        in.seek(dataStart + tensor.offset);
        float[] values = new float[(int) count];
        for (int i = 0; i < values.length; i++) values[i] = Float.intBitsToFloat((int) readU32(in));
        return values;
    }

    private static void skipValue(RandomAccessFile in, int type) throws IOException {
        switch (type) {
            case 0, 1, 7 -> in.skipBytes(1);
            case 2, 3 -> in.skipBytes(2);
            case 4, 5, 6 -> in.skipBytes(4);
            case 10, 11, 12 -> in.skipBytes(8);
            case 8 -> readString(in);
            case 9 -> {
                int elementType = (int) readU32(in);
                long count = readU64(in);
                for (long i = 0; i < count; i++) skipValue(in, elementType);
            }
            default -> throw new IOException("Unsupported GGUF metadata type " + type);
        }
    }

    private static String readString(RandomAccessFile in) throws IOException {
        long length = readU64(in);
        require(length >= 0 && length <= 16 * 1024 * 1024, "Invalid GGUF string length");
        byte[] bytes = new byte[(int) length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long readU32(RandomAccessFile in) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
    }

    private static long readU64(RandomAccessFile in) throws IOException {
        return Long.reverseBytes(in.readLong());
    }

    private static long align(long value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
