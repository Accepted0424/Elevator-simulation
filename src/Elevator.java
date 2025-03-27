import com.oocourse.elevator1.PersonRequest;
import com.oocourse.elevator1.TimableOutput;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

public class Elevator implements Runnable {
    private final int id;
    private int curFloor = 1;
    private int lastFloor = 1;
    private final RequestQueue requestQueue;
    private final Queue<PersonRequest> insideQueue;
    private final int capacity = 6;
    private static final long timePerFloor = 400;
    private static final long minTimeOpen2Close = 400; // 400ms

    public Elevator(int id) {
        this.id = id;
        this.insideQueue = new PriorityQueue<>(
                Comparator.comparing(PersonRequest::getPriority).reversed());
        this.requestQueue = new RequestQueue();
    }

    public int getId() {
        return id;
    }

    public Queue<PersonRequest> getInsideQueue() {
        return insideQueue;
    }

    public RequestQueue getRequestQueue() {
        return requestQueue;
    }

    public int getCurFloor() {
        return curFloor;
    }

    private Status update() {
        /* LOOK */
        if (hasPersonOut() || hashPersonIn()) {
            return Status.OPEN;
        }
        if (insideQueue.isEmpty()) {
            if (requestQueue.isEmpty()) {
                return Status.WAIT;
            } else {
                return updateDirection();
            }
        } else {
            return Status.MOVE;
        }
    }

    private boolean hasPersonOut() {
        for (PersonRequest pr : insideQueue) {
            if (intOf(pr.getToFloor()) == curFloor) {
                return true;
            }
        }
        return false;
    }

    private boolean hashPersonIn() {
        return requestQueue.getRequestsAt(curFloor) != null
                && !requestQueue.getRequestsAt(curFloor).isEmpty();
    }

    private Status updateDirection() {
        int nextFloor = requestQueue.nextTargetFloor(curFloor);
        if (nextFloor > curFloor) {
            return Status.MOVE;
        } else if (nextFloor == curFloor) {
            return Status.WAIT;
        } else {
            return Status.REVERSE;
        }
    }

    public void execute() throws InterruptedException {
        if (lastFloor != curFloor) {
            TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
        }
        lastFloor = curFloor;
        Status status = update();
        switch (status) {
            case OPEN:
                TimableOutput.println(String.format("OPEN-%s-%d", formatFloor(curFloor), id));
                personOut();
                personIn();
                Thread.sleep(minTimeOpen2Close);
                TimableOutput.println(String.format("CLOSE-%s-%d", formatFloor(curFloor), id));
                break;
            case MOVE:
                Thread.sleep(timePerFloor);
                curFloor++;
                if (curFloor == 0) {
                    curFloor++;
                }
                break;
            case REVERSE:
                Thread.sleep(timePerFloor);
                curFloor--;
                if (curFloor == 0) {
                    curFloor--;
                }
                break;
            default:
                break;
        }
    }

    private void personOut() {
        for (PersonRequest pr : insideQueue) {
            if (intOf(pr.getToFloor()) == curFloor) {
                TimableOutput.println(String.format("OUT-%d-%s-%d", pr.getPersonId(),
                    formatFloor(curFloor), id));
                insideQueue.remove(pr);
            }
        }
    }

    private void personIn() {
        while (requestQueue.getRequestsAt(curFloor) != null &&
                !requestQueue.getRequestsAt(curFloor).isEmpty() &&
                insideQueue.size() <= capacity) {
            PersonRequest inPerson = requestQueue.poll(curFloor);
            TimableOutput.println(String.format("IN-%d-%s-%d",
                inPerson.getPersonId(), formatFloor(curFloor), id));
            insideQueue.add(inPerson);
        }
    }

    private String formatFloor(int floor) {
        if (floor < 0) {
            return String.format("B%d", Math.abs(floor));
        } else {
            return String.format("F%d", Math.abs(floor));
        }
    }

    private int intOf(String floor) {
        if (floor.startsWith("B")) {
            return (-Integer.parseInt(floor.substring(1)));
        } else {
            return (Integer.parseInt(floor.substring(1)));
        }
    }

    @Override
    public void run() {
        synchronized (requestQueue) {
            while (true) {
                while (requestQueue.isEmpty() && !requestQueue.isEnd() && insideQueue.isEmpty()) {
                    try {
                        // System.out.println(Thread.currentThread().getName() + ": Waiting for request...");
                        requestQueue.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (requestQueue.isEnd()) {
                    return;
                }
                try {
                    execute();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
