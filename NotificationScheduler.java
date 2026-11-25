import java.io.*;
import java.util.*;

public class NotificationScheduler {

    // Represents a scheduled notification
    static class Notification implements Comparable<Notification> {
        final String id;
        final String message;
        int timeMinutes; // Minutes since 00:00 (0 to 1439)
        final int repeatIntervalMinutes; // 0 if non-recurring, > 0 if recurring
        boolean cancelled;

        Notification(String id, String message, int timeMinutes, int repeatIntervalMinutes) {
            this.id = id;
            this.message = message;
            this.timeMinutes = timeMinutes;
            this.repeatIntervalMinutes = repeatIntervalMinutes;
            this.cancelled = false;
        }

        @Override
        public int compareTo(Notification other) {
            // Greedy ordering: earliest scheduled time first; tie-break by ID
            if (this.timeMinutes != other.timeMinutes) {
                return Integer.compare(this.timeMinutes, other.timeMinutes);
            }
            return this.id.compareTo(other.id);
        }

        String serialize() {
            return id + "|" + escape(message) + "|" + timeMinutes + "|" + repeatIntervalMinutes + "|" + cancelled;
        }

        static Notification deserialize(String line) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 5) return null;
            String id = parts[0];
            String message = unescape(parts[1]);
            int timeMinutes = Integer.parseInt(parts[2]);
            int repeatInterval = Integer.parseInt(parts[3]);
            boolean cancelled = Boolean.parseBoolean(parts[4]);
            Notification n = new Notification(id, message, timeMinutes, repeatInterval);
            n.cancelled = cancelled;
            return n;
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n");
        }

        private static String unescape(String s) {
            return s.replace("\\n", "\n").replace("\\p", "|").replace("\\\\", "\\");
        }
    }

    // Fixed-capacity Circular Queue for recurring reminders
    static class CircularReminderQueue {
        private final Notification[] buffer;
        private int head = 0;
        private int tail = 0;
        private int count = 0;

        CircularReminderQueue(int capacity) {
            this.buffer = new Notification[Math.max(1, capacity)];
        }

        boolean isFull() {
            return count == buffer.length;
        }

        boolean isEmpty() {
            return count == 0;
        }

        int size() {
            return count;
        }

        boolean enqueue(Notification n) {
            if (n == null || n.cancelled || n.repeatIntervalMinutes <= 0) return false;
            if (isFull()) {
                return false;
            }
            buffer[tail] = n;
            tail = (tail + 1) % buffer.length;
            count++;
            return true;
        }

        Notification dequeue() {
            if (isEmpty()) return null;
            Notification item = buffer[head];
            buffer[head] = null;
            head = (head + 1) % buffer.length;
            count--;
            return item;
        }

        List<Notification> toList() {
            List<Notification> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Notification item = buffer[(head + i) % buffer.length];
                if (item != null && !item.cancelled) {
                    list.add(item);
                }
            }
            return list;
        }
    }

    private static final String DATA_FILE = "notifications.txt";

    // PriorityQueue ordered by scheduled time (greedy earliest-due selection)
    private final PriorityQueue<Notification> scheduledHeap = new PriorityQueue<>();

    // Queue for notifications that are due / ready to send
    private final Queue<Notification> readyQueue = new LinkedList<>();

    // Circular Queue for managing recurring reminders
    private final CircularReminderQueue recurringCircularQueue = new CircularReminderQueue(100);

    // Tracks active IDs to prevent duplicate active notifications & support cancellation
    private final Map<String, Notification> notificationRegistry = new LinkedHashMap<>();

    private int idCounter = 101;

    // Validates HH:mm (24-hour format, 00:00 to 23:59) and converts to minutes since midnight
    public static int parseAndValidateTime(String hhmm) {
        if (hhmm == null) return -1;
        String trimmed = hhmm.trim();
        if (!trimmed.matches("^([01]?\\d|2[0-3]):([0-5]\\d)$")) {
            return -1;
        }
        String[] parts = trimmed.split(":");
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
            return -1;
        }
        return hours * 60 + minutes;
    }

    public static String formatTime(int totalMinutes) {
        int wrapped = ((totalMinutes % 1440) + 1440) % 1440;
        int h = wrapped / 60;
        int m = wrapped % 60;
        return String.format("%02d:%02d", h, m);
    }

    private String generateNextId() {
        while (notificationRegistry.containsKey("N" + idCounter)) {
            idCounter++;
        }
        return "N" + (idCounter++);
    }

    public Notification scheduleNotification(String timeStr, String message, int repeatIntervalMinutes) {
        int timeMinutes = parseAndValidateTime(timeStr);
        if (timeMinutes < 0 || repeatIntervalMinutes < 0) {
            System.out.println("Invalid time. Notification not scheduled.");
            return null;
        }

        // Prevent duplicate active notification with identical time, message, and interval
        for (Notification existing : notificationRegistry.values()) {
            if (!existing.cancelled
                    && existing.timeMinutes == timeMinutes
                    && existing.message.equalsIgnoreCase(message)
                    && existing.repeatIntervalMinutes == repeatIntervalMinutes) {
                System.out.println("Duplicate notification ignored: " + existing.id);
                return null;
            }
        }

        String id = generateNextId();
        Notification n = new Notification(id, message, timeMinutes, repeatIntervalMinutes);
        notificationRegistry.put(id, n);
        scheduledHeap.offer(n);

        if (repeatIntervalMinutes > 0) {
            if (!recurringCircularQueue.enqueue(n)) {
                System.out.println("Warning: Circular reminder queue full; recurring reminder " + id + " still scheduled once.");
            }
        }

        System.out.println("Notification scheduled: " + n.id + " | " + formatTime(n.timeMinutes));
        return n;
    }

    public boolean cancelNotification(String id) {
        if (id == null) return false;
        Notification n = notificationRegistry.get(id.trim().toUpperCase());
        if (n == null || n.cancelled) {
            System.out.println("Notification not found or already cancelled: " + id);
            return false;
        }
        n.cancelled = true;
        scheduledHeap.remove(n);
        readyQueue.remove(n);
        System.out.println("Notification cancelled: " + n.id);
        return true;
    }

    // Greedy step: repeatedly picks the earliest due notification (<= currentTimeMinutes)
    public void processDue(String currentTimeStr) {
        int currentMinutes = parseAndValidateTime(currentTimeStr);
        if (currentMinutes < 0) {
            System.out.println("Invalid time. Cannot process due notifications.");
            return;
        }

        // Clean any cancelled items at the top of the heap
        purgeCancelledFromHeap();

        if (scheduledHeap.isEmpty() || scheduledHeap.peek().timeMinutes > currentMinutes) {
            System.out.println("No notifications ready to send");
            return;
        }

        // Greedily move all due notifications in earliest-scheduled order into readyQueue
        while (!scheduledHeap.isEmpty()) {
            purgeCancelledFromHeap();
            if (scheduledHeap.isEmpty()) break;

            Notification nextDue = scheduledHeap.peek();
            if (nextDue.timeMinutes > currentMinutes) {
                break;
            }
            scheduledHeap.poll();
            if (!nextDue.cancelled) {
                readyQueue.offer(nextDue);
            }
        }

        if (readyQueue.isEmpty()) {
            System.out.println("No notifications ready to send");
            return;
        }

        while (!readyQueue.isEmpty()) {
            Notification toSend = readyQueue.poll();
            if (toSend == null || toSend.cancelled) continue;

            System.out.println("Notification scheduled for " + formatTime(toSend.timeMinutes)
                    + " is sent first. [" + toSend.id + ": " + toSend.message + "]");

            // If recurring, reschedule next occurrence via circular reminder logic
            if (toSend.repeatIntervalMinutes > 0 && !toSend.cancelled) {
                int nextTime = (toSend.timeMinutes + toSend.repeatIntervalMinutes) % 1440;
                Notification nextOccurrence = new Notification(
                        toSend.id,
                        toSend.message,
                        nextTime,
                        toSend.repeatIntervalMinutes
                );
                notificationRegistry.put(nextOccurrence.id, nextOccurrence);
                scheduledHeap.offer(nextOccurrence);
                recurringCircularQueue.enqueue(nextOccurrence);
            }
        }
    }

    public void viewPending() {
        purgeCancelledFromHeap();
        if (scheduledHeap.isEmpty()) {
            System.out.println("No pending notifications.");
            return;
        }

        List<Notification> sorted = new ArrayList<>(scheduledHeap);
        Collections.sort(sorted);

        System.out.println("--- Pending Notifications (Earliest First) ---");
        for (Notification n : sorted) {
            if (!n.cancelled) {
                String repeatInfo = n.repeatIntervalMinutes > 0
                        ? " (Repeats every " + n.repeatIntervalMinutes + "m)"
                        : "";
                System.out.println(n.id + " | " + formatTime(n.timeMinutes) + " | " + n.message + repeatInfo);
            }
        }
    }

    public void viewRecurringReminders() {
        List<Notification> items = recurringCircularQueue.toList();
        if (items.isEmpty()) {
            System.out.println("No recurring reminders in circular queue.");
            return;
        }
        System.out.println("--- Circular Queue: Recurring Reminders (" + items.size() + ") ---");
        for (Notification n : items) {
            System.out.println(n.id + " | " + formatTime(n.timeMinutes)
                    + " | every " + n.repeatIntervalMinutes + "m | " + n.message);
        }
    }

    private void purgeCancelledFromHeap() {
        while (!scheduledHeap.isEmpty() && scheduledHeap.peek().cancelled) {
            scheduledHeap.poll();
        }
    }

    public void loadFromFile() {
        File file = new File(DATA_FILE);
        if (!file.exists()) {
            return;
        }
        scheduledHeap.clear();
        readyQueue.clear();
        notificationRegistry.clear();
        idCounter = 101;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Notification n = Notification.deserialize(line);
                if (n != null && !n.cancelled && n.timeMinutes >= 0 && n.timeMinutes < 1440) {
                    notificationRegistry.put(n.id, n);
                    scheduledHeap.offer(n);
                    if (n.repeatIntervalMinutes > 0) {
                        recurringCircularQueue.enqueue(n);
                    }
                    if (n.id.startsWith("N")) {
                        try {
                            int num = Integer.parseInt(n.id.substring(1));
                            if (num >= idCounter) idCounter = num + 1;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Error loading " + DATA_FILE + ": " + e.getMessage());
        }
    }

    public void saveToFile() {
        purgeCancelledFromHeap();
        List<Notification> toSave = new ArrayList<>(scheduledHeap);
        Collections.sort(toSave);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(DATA_FILE, false))) {
            for (Notification n : toSave) {
                if (!n.cancelled) {
                    writer.write(n.serialize());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            System.out.println("Error saving to " + DATA_FILE + ": " + e.getMessage());
        }
    }

    private static void runSampleTestCases() {
        System.out.println("=== Running Problem Statement Test Cases ===");
        NotificationScheduler demo = new NotificationScheduler();

        // Test Case 1: Normal Input -> Schedule a notification for 10:30
        System.out.println("\n[Test Case 1]");
        demo.scheduleNotification("10:30", "Team standup", 0);

        // Test Case 2: Boundary / Minimum Input -> No notification is due yet
        System.out.println("\n[Test Case 2]");
        demo.processDue("09:00");

        // Test Case 3: Invalid Input -> Schedule notification with invalid time 25:90
        System.out.println("\n[Test Case 3]");
        demo.scheduleNotification("25:90", "Invalid test", 0);

        // Test Case 4: Different Valid Scenario -> 10:20 and 10:10; process at 10:15
        System.out.println("\n[Test Case 4]");
        NotificationScheduler tc4 = new NotificationScheduler();
        tc4.scheduleNotification("10:20", "Later alert", 0);
        tc4.scheduleNotification("10:10", "Earlier alert", 0);
        tc4.processDue("10:15");
        System.out.println("===========================================\n");
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("--test")) {
            runSampleTestCases();
            return;
        }

        NotificationScheduler scheduler = new NotificationScheduler();
        scheduler.loadFromFile();

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.println("\n=== Notification Scheduler ===");
            System.out.println("1. Schedule Notification");
            System.out.println("2. Cancel Notification");
            System.out.println("3. Process Due Notifications");
            System.out.println("4. View Pending Notifications");
            System.out.println("5. View Circular Recurring Reminders");
            System.out.println("6. Run Built-in Test Cases");
            System.out.println("7. Save & Exit");
            System.out.print("Choose an option: ");

            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    System.out.print("Enter time (HH:mm): ");
                    String time = scanner.nextLine();
                    System.out.print("Enter message: ");
                    String msg = scanner.nextLine();
                    System.out.print("Repeat interval in minutes (0 for none): ");
                    String repStr = scanner.nextLine().trim();
                    int repeat = 0;
                    try {
                        repeat = Integer.parseInt(repStr);
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid repeat interval. Using 0.");
                    }
                    scheduler.scheduleNotification(time, msg, repeat);
                    break;
                case "2":
                    System.out.print("Enter Notification ID to cancel (e.g., N101): ");
                    String id = scanner.nextLine();
                    scheduler.cancelNotification(id);
                    break;
                case "3":
                    System.out.print("Enter current time (HH:mm): ");
                    String currTime = scanner.nextLine();
                    scheduler.processDue(currTime);
                    break;
                case "4":
                    scheduler.viewPending();
                    break;
                case "5":
                    scheduler.viewRecurringReminders();
                    break;
                case "6":
                    runSampleTestCases();
                    break;
                case "7":
                    scheduler.saveToFile();
                    System.out.println("Changes saved to " + DATA_FILE + ". Exiting.");
                    running = false;
                    break;
                default:
                    System.out.println("Invalid choice. Please select 1-7.");
            }
        }
        scanner.close();
    }
}