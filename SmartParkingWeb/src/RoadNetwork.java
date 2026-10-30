
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Weighted road graph.
 * Edge weights represent simulated travel time in minutes.
 */
public class RoadNetwork {

    public static final int UNREACHABLE = Integer.MAX_VALUE / 4;

    private static class Edge {
        String destination;
        int minutes;

        Edge(String destination, int minutes) {
            this.destination = destination;
            this.minutes = minutes;
        }
    }

    private static class NodeDistance
            implements Comparable<NodeDistance> {

        String node;
        int distance;

        NodeDistance(String node, int distance) {
            this.node = node;
            this.distance = distance;
        }

        @Override
        public int compareTo(NodeDistance other) {
            int result = Integer.compare(
                    this.distance, other.distance);

            if (result != 0) {
                return result;
            }

            return this.node.compareTo(other.node);
        }
    }

    private final Map<String, ArrayList<Edge>> adjacency =
            new HashMap<>();

    public void addUndirectedEdge(
            String first, String second, int minutes) {

        if (first == null || second == null || minutes < 0) {
            throw new IllegalArgumentException(
                    "Valid road endpoints and non-negative travel time required.");
        }

        adjacency.computeIfAbsent(
                first, key -> new ArrayList<>())
                .add(new Edge(second, minutes));

        adjacency.computeIfAbsent(
                second, key -> new ArrayList<>())
                .add(new Edge(first, minutes));
    }

    // Dijkstra's algorithm using PriorityQueue as a min-heap.
    public Map<String, Integer> shortestTravelTimes(
            String source) {

        Map<String, Integer> distance = new HashMap<>();

        for (String node : adjacency.keySet()) {
            distance.put(node, UNREACHABLE);
        }

        if (!adjacency.containsKey(source)) {
            return distance;
        }

        PriorityQueue<NodeDistance> minHeap =
                new PriorityQueue<>();

        distance.put(source, 0);
        minHeap.add(new NodeDistance(source, 0));

        while (!minHeap.isEmpty()) {

            NodeDistance current = minHeap.poll();

            if (current.distance !=
                    distance.get(current.node)) {
                continue;
            }

            for (Edge edge :
                    adjacency.getOrDefault(
                            current.node, new ArrayList<>())) {

                int candidate =
                        current.distance + edge.minutes;

                if (candidate <
                        distance.getOrDefault(
                                edge.destination, UNREACHABLE)) {

                    distance.put(
                            edge.destination, candidate);

                    minHeap.add(new NodeDistance(
                            edge.destination, candidate));
                }
            }
        }

        return distance;
    }
}