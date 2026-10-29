import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores parking-system events in memory and in event_history.txt.
 * The file is created in the application's current working directory.
 */
public class EventHistory {
    private final ArrayList<String> events = new ArrayList<>();
    private final Path historyFile = Path.of("event_history.txt");

    public EventHistory() {
        loadPreviousEvents();
    }

    public void record(int time, String eventType, String description) {
        String timeText = String.format("%02d:%02d", time / 60, time % 60);
        String event = timeText + " | " + eventType + " | " + description;
        events.add(event);
        appendEvent(event);
    }

    public int getEventCount() {
        return events.size();
    }

    public List<String> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void displayEvents() {
        if (events.isEmpty()) {
            System.out.println("No events recorded yet.");
            return;
        }

        System.out.println("Total events recorded: " + events.size());
        System.out.println("History file: " + historyFile.toAbsolutePath());
        System.out.println("------------------------------------------------------------");

        for (int i = 0; i < events.size(); i++) {
            System.out.printf("%02d. %s%n", i + 1, events.get(i));
        }
    }

    private void loadPreviousEvents() {
        if (!Files.exists(historyFile)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(historyFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    events.add(line);
                }
            }
        } catch (IOException e) {
            System.out.println("Warning: Previous event history could not be loaded: "
                    + e.getMessage());
        }
    }

    private void appendEvent(String event) {
        try {
            Path parent = historyFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (BufferedWriter writer = Files.newBufferedWriter(
                    historyFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                writer.write(event);
                writer.newLine();
            }
        } catch (IOException e) {
            System.out.println("Warning: Event could not be saved: " + e.getMessage());
        }
    }
}
