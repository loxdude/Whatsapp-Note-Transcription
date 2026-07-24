import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.tensorflow.lite.schema.Model;
import org.tensorflow.lite.schema.SubGraph;
import org.tensorflow.lite.schema.Tensor;
import org.tensorflow.lite.schema.TensorType;

public final class TfliteContractInspector {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.READ)) {
            ByteBuffer bytes = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN);
            Model model = Model.getRootAsModel(bytes);
            System.out.printf("version=%d description=%s subgraphs=%d%n",
                    model.version(), model.description(), model.subgraphsLength());
            for (int graphIndex = 0; graphIndex < model.subgraphsLength(); graphIndex++) {
                SubGraph graph = model.subgraphs(graphIndex);
                System.out.printf("%nsubgraph[%d] name=%s tensors=%d operators=%d%n",
                        graphIndex, graph.name(), graph.tensorsLength(), graph.operatorsLength());
                System.out.println("inputs:");
                for (int i = 0; i < graph.inputsLength(); i++) {
                    printTensor(i, graph.inputs(i), graph.tensors(graph.inputs(i)));
                }
                System.out.println("outputs:");
                for (int i = 0; i < graph.outputsLength(); i++) {
                    printTensor(i, graph.outputs(i), graph.tensors(graph.outputs(i)));
                }
            }
        }
    }

    private static void printTensor(int slot, int tensorIndex, Tensor tensor) {
        int[] shape = new int[tensor.shapeLength()];
        for (int i = 0; i < shape.length; i++) shape[i] = tensor.shape(i);
        System.out.printf("  slot=%d tensor=%d name=%s type=%s shape=%s variable=%s%n",
                slot, tensorIndex, tensor.name(), TensorType.name(tensor.type()),
                Arrays.toString(shape), tensor.isVariable());
    }
}
