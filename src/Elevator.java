import com.oocourse.elevator1.PersonRequest;
import com.oocourse.elevator1.TimableOutput;

import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Queue;

public class Elevator implements Runnable {
    private final int id;
    private int curFloor = 1;
    private int lastFloor = 1;
    private final RequestQueue requestQueue;
    private final Queue<PersonRequest> insideQueue;
    private static final int capacity = 6;
    private static final long timePerFloor = 400;
    private static final long minTimeOpen2Close = 400; // 400ms
    private final Object lock = new Object();

    public Elevator(int id) {
        this.id = id;
        this.insideQueue = new PriorityQueue<>(
                Comparator.comparing(PersonRequest::getPriority));
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

    private double getInsideUpPri() {
        double sum = 0;
        for (PersonRequest insidePr: insideQueue) {
            if (intOf(insidePr.getToFloor()) > curFloor) {
                int floorDiff = intOf(insidePr.getToFloor()) > 0 && curFloor < 0 ?
                    intOf(insidePr.getToFloor()) - curFloor - 1 :
                    intOf(insidePr.getToFloor()) - curFloor;
                sum += (double) insidePr.getPriority() / (double) floorDiff;
            }
        }
        return sum;
    }

    private double getInsideDownPri() {
        double sum = 0;
        for (PersonRequest insidePr: insideQueue) {
            if (intOf(insidePr.getToFloor()) < curFloor) {
                int floorDiff = curFloor > 0 && intOf(insidePr.getToFloor()) < 0 ?
                    curFloor - intOf(insidePr.getToFloor()) - 1 :
                    curFloor - intOf(insidePr.getToFloor());
                sum += (double) insidePr.getPriority() / (double) floorDiff;
            }
        }
        return sum;
    }

    private Status update() {
        /* LOOK */
        if (hasPersonOut() || hasPersonIn() || needRearrange()) {
            return Status.OPEN;
        }
        if (insideQueue.isEmpty()) {
            if (requestQueue.isEmpty()) {
                if (MainClass.debug) {
                    TimableOutput.println("No requests to process, WAITING...");
                }
                return Status.WAIT;
            } else {
                return updateDirection();
            }
        } else {
            if (Double.compare(getInsideUpPri(), getInsideDownPri()) >= 0) {
                return Status.MOVE;
            } else {
                return Status.REVERSE;
            }
            /*
            if (intOf(insideQueue.peek().getToFloor()) > curFloor) {
                return Status.MOVE;
            } else if (intOf(insideQueue.peek().getToFloor()) < curFloor) {
                return Status.REVERSE;
            } else {
                return Status.OPEN;
            }*/
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

    private boolean hasPersonInButFull() {
        return requestQueue.getRequestsAt(curFloor) != null
                && !requestQueue.getRequestsAt(curFloor).isEmpty() &&
                insideQueue.size() == capacity;
    }

    private boolean hasPersonIn() {
        return requestQueue.getRequestsAt(curFloor) != null
                && !requestQueue.getRequestsAt(curFloor).isEmpty() && insideQueue.size() < capacity;
    }

    private Status updateDirection() {
        // insideQueue为空 requestQueue不为空
        int nextFloor = requestQueue.nextTargetFloor(curFloor);
        if (nextFloor > curFloor) {
            return Status.MOVE;
        } else if (nextFloor == curFloor) {
            if (MainClass.debug) {
                TimableOutput.println(MainClass.BLUE +
                    "nextFloor == curFloor, WAITING..." +
                    MainClass.RESET);
            }
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
                rearrange();
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
            case WAIT:
                // if (MainClass.debug) {
                //  TimableOutput.println(MainClass.BLUE + "WAITING..." + MainClass.RESET);
                // }
                break;
            default:
                if (MainClass.debug) {
                    TimableOutput.println(MainClass.BLUE + "DEFAULT: " + status + MainClass.RESET);
                }
                break;
        }
    }

    private boolean needRearrange() {
        if (hasPersonInButFull() && requestQueue.getRequestsAt(curFloor) != null &&
            !requestQueue.getRequestsAt(curFloor).isEmpty()) {
            return requestQueue.getRequestsAt(curFloor).peek().getPriority() >
                    5 * insideQueue.peek().getPriority();
        }
        return false;
    }

    private void rearrange() {
        if (hasPersonInButFull() && requestQueue.getRequestsAt(curFloor) != null &&
            !requestQueue.getRequestsAt(curFloor).isEmpty()) {
            while (requestQueue.getRequestsAt(curFloor) != null &&
                    !requestQueue.getRequestsAt(curFloor).isEmpty() &&
                    requestQueue.getRequestsAt(curFloor).peek().getPriority() >
                    5 * insideQueue.peek().getPriority()) {
                TimableOutput.println(String.format("OUT-%d-%s-%d",
                    insideQueue.peek().getPersonId(), formatFloor(curFloor), id));
                TimableOutput.println(String.format("IN-%d-%s-%d",
                    requestQueue.getRequestsAt(curFloor).peek().getPersonId(),
                    formatFloor(curFloor), id));
                requestQueue.offer(insideQueue.poll());
                insideQueue.add(requestQueue.poll(curFloor));
            }
        }
    }

    private void personOut() {
        Iterator<PersonRequest> iterator = insideQueue.iterator();
        while (iterator.hasNext()) {
            PersonRequest pr = iterator.next();
            if (intOf(pr.getToFloor()) == curFloor) {
                TimableOutput.println(String.format("OUT-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
                iterator.remove();  // 安全删除
            }
        }
        if (MainClass.debug) {
            TimableOutput.println(MainClass.BLUE + "insideQueue: " + insideQueue + MainClass.RESET);
        }
        if (MainClass.debug) {
            TimableOutput.println(MainClass.BLUE +
                "requestQueue: " + requestQueue.getRequestsQueue() +
                MainClass.RESET);
        }
    }

    private void personIn() {
        while (requestQueue.getRequestsAt(curFloor) != null &&
                !requestQueue.getRequestsAt(curFloor).isEmpty() &&
                insideQueue.size() < capacity) {
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
        while (true) {
            if (requestQueue.isEnd() && requestQueue.isEmpty() && insideQueue.isEmpty()) {
                if (MainClass.debug) {
                    TimableOutput.println(MainClass.YELLOW +
                        Thread.currentThread().getName() + " END" +
                        MainClass.RESET);
                }
                return;
            }
            if (requestQueue.isEmpty() && insideQueue.isEmpty()) {
                try {
                    requestQueue.myWait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            try {
                execute();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
