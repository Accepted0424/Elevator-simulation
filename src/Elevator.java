import com.oocourse.elevator2.PersonRequest;
import com.oocourse.elevator2.ScheRequest;
import com.oocourse.elevator2.TimableOutput;

import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Queue;

public class Elevator implements Runnable {
    private final int id;
    private int curFloor = 1;
    private final RequestQueue requestQueue;
    private final Queue<PersonRequest> insideQueue;
    private static final int capacity = 6;
    public volatile boolean inSchedule = false;
    private final Object scheduleLock = new Object();
    private int targetScheFloor;
    private static final long defaultTimePerFloor = 400;
    private long timePerFloor = 400;
    private static long timeStop = 1000;
    private static final long minTimeOpen2Close = 400; // 400ms
    private final Dispatch dispatch;

    public Elevator(int id, Dispatch dispatch) {
        this.id = id;
        this.dispatch = dispatch;
        this.requestQueue = new RequestQueue();
        this.insideQueue = new PriorityQueue<>(
                Comparator.comparing(PersonRequest::getPriority));
    }

    public int getId() {
        return id;
    }

    public synchronized boolean isFull() {
        return insideQueue.size() == capacity;
    }

    public RequestQueue getRequestQueue() {
        return requestQueue;
    }

    public int getCurFloor() {
        return curFloor;
    }

    public boolean isInSchedule() {
        synchronized (scheduleLock) {
            scheduleLock.notifyAll();
            return inSchedule;
        }
    }

    public synchronized void scheduleStart(ScheRequest sr) {
        synchronized (scheduleLock) {
            inSchedule = true;
            timePerFloor = (long) (sr.getSpeed() * 1000);
            targetScheFloor = intOf(sr.getToFloor());
            TimableOutput.println(String.format("SCHE-BEGIN-%d", id));
            scheduleLock.notifyAll();
        }
    }

    public synchronized void scheduleEnd() {
        synchronized (scheduleLock) {
            inSchedule = false;
            timePerFloor = defaultTimePerFloor;
            TimableOutput.println(String.format("SCHE-END-%d", id));
            if (dispatch.allElevatorsBusy) {
                dispatch.hasFreeElevator();
            }
            scheduleLock.notifyAll();
        }
    }

    private synchronized double getInsideUpPri() {
        double sum = 0;
        for (PersonRequest insidePr : insideQueue) {
            if (intOf(insidePr.getToFloor()) > curFloor) {
                int floorDiff = intOf(insidePr.getToFloor()) > 0 && curFloor < 0 ?
                    intOf(insidePr.getToFloor()) - curFloor - 1 :
                    intOf(insidePr.getToFloor()) - curFloor;

                sum += (double) insidePr.getPriority() / (double) floorDiff;
            }
        }
        return sum;
    }

    private synchronized double getInsideDownPri() {
        double sum = 0;
        for (PersonRequest insidePr : insideQueue) {
            if (intOf(insidePr.getToFloor()) < curFloor) {
                int floorDiff = curFloor > 0 && intOf(insidePr.getToFloor()) < 0 ?
                    curFloor - intOf(insidePr.getToFloor()) - 1 :
                    curFloor - intOf(insidePr.getToFloor());
                sum += (double) insidePr.getPriority() / (double) floorDiff;
            }
        }
        return sum;
    }

    private synchronized Status update() {
        /* LOOK */
        // 处于调度状态
        if (inSchedule) {
            if (targetScheFloor > curFloor) {
                return Status.MOVE;
            } else {
                return Status.REVERSE;
            }
        }
        // 处于非调度状态
        if (hasPersonOut() || hasPersonIn() || needRearrange()) {
            return Status.OPEN;
        }
        if (insideQueue.isEmpty()) {
            if (requestQueue.isEmpty()) {
                if (MainClass.debug) {
                    TimableOutput.println("In Eleva" + "No requests to process, WAITING...");
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
        }
    }

    private synchronized boolean hasPersonOut() {
        for (PersonRequest pr : insideQueue) {
            if (intOf(pr.getToFloor()) == curFloor) {
                return true;
            }
        }
        return false;
    }

    private synchronized boolean hasPersonInButFull() {
        return requestQueue.getRequestsAt(curFloor) != null
                && !requestQueue.getRequestsAt(curFloor).isEmpty() &&
                insideQueue.size() == capacity;
    }

    private synchronized boolean hasPersonIn() {
        return requestQueue.getRequestsAt(curFloor) != null
                && !requestQueue.getRequestsAt(curFloor).isEmpty() && insideQueue.size() < capacity;
    }

    private Status updateDirection() {
        // insideQueue为空 requestQueue不为空
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
        if (inSchedule && curFloor == targetScheFloor) {
            TimableOutput.println(String.format("OPEN-%s-%d", formatFloor(curFloor), id));
            allPersonOut();
            Thread.sleep(timeStop);
            TimableOutput.println(String.format("CLOSE-%s-%d", formatFloor(curFloor), id));
            scheduleEnd();
            return;
        }
        Status status = update();
        switch (status) {
            case OPEN:
                if (!inSchedule) {
                    TimableOutput.println(String.format("OPEN-%s-%d", formatFloor(curFloor), id));
                    personOut();
                    personIn();
                    rearrange();
                    Thread.sleep(minTimeOpen2Close);
                    TimableOutput.println(String.format("CLOSE-%s-%d", formatFloor(curFloor), id));
                }
                break;
            case MOVE:
                Thread.sleep(timePerFloor);
                curFloor++;
                if (curFloor == 0) {
                    curFloor++;
                }
                TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                break;
            case REVERSE:
                Thread.sleep(timePerFloor);
                curFloor--;
                if (curFloor == 0) {
                    curFloor--;
                }
                TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                break;
            case WAIT:
                if (MainClass.debug) {
                    TimableOutput.println(MainClass.BLUE + "WAITING..." + MainClass.RESET);
                }
                break;
            default:
                if (MainClass.debug) {
                    TimableOutput.println(MainClass.BLUE + "DEFAULT: " + status + MainClass.RESET);
                }
                break;
        }
    }

    private synchronized boolean needRearrange() {
        if (hasPersonInButFull() && requestQueue.getRequestsAt(curFloor) != null &&
            !requestQueue.getRequestsAt(curFloor).isEmpty()) {
            return requestQueue.getRequestsAt(curFloor).peek().getPriority() >
                    5 * insideQueue.peek().getPriority();
        }
        return false;
    }

    private synchronized void rearrange() {
        if (hasPersonInButFull() && requestQueue.getRequestsAt(curFloor) != null &&
            !requestQueue.getRequestsAt(curFloor).isEmpty()) {
            while (requestQueue.getRequestsAt(curFloor) != null &&
                    !requestQueue.getRequestsAt(curFloor).isEmpty() &&
                    requestQueue.getRequestsAt(curFloor).peek().getPriority() >
                            5 * insideQueue.peek().getPriority()) {
                TimableOutput.println(String.format("OUT-F-%d-%s-%d",
                    insideQueue.peek().getPersonId(), formatFloor(curFloor), id));
                TimableOutput.println(String.format("IN-%d-%s-%d",
                    requestQueue.getRequestsAt(curFloor).peek().getPersonId(),
                    formatFloor(curFloor), id));
                dispatch.offer(insideQueue.peek(), true, curFloor);
                requestQueue.offer(insideQueue.poll());
                insideQueue.add(requestQueue.poll(curFloor));
            }
        }
    }

    private synchronized void personOut() throws InterruptedException {
        Iterator<PersonRequest> iterator = insideQueue.iterator();
        while (iterator.hasNext()) {
            PersonRequest pr = iterator.next();
            if (intOf(pr.getToFloor()) == curFloor) {
                TimableOutput.println(String.format("OUT-S-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
                if (dispatch.allElevatorsBusy) {
                    dispatch.hasFreeElevator();
                }
                iterator.remove();  // 安全删除
            }
        }
    }

    private synchronized void allPersonOut() throws InterruptedException {
        Iterator<PersonRequest> iterator = insideQueue.iterator();
        while (iterator.hasNext()) {
            PersonRequest pr = iterator.next();
            if (intOf(pr.getToFloor()) == curFloor) {
                TimableOutput.println(String.format("OUT-S-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
                if (dispatch.allElevatorsBusy) {
                    dispatch.hasFreeElevator();
                }
            } else {
                dispatch.offer(pr, true, curFloor);
                TimableOutput.println(String.format("OUT-F-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
            }
            iterator.remove();  // 安全删除
        }
    }

    private synchronized void personIn() {
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
            if (requestQueue.isEnd() && requestQueue.isEmpty() &&
                insideQueue.isEmpty() && !inSchedule && dispatch.isEmpty()) {
                //TimableOutput.println(Thread.currentThread().getName() + " ends!!!!!!!!!!!!!!!!!!");
                return;
            }
            while (!requestQueue.isEnd() && !dispatch.isEmpty() &&
                requestQueue.isEmpty() && insideQueue.isEmpty() && !inSchedule) {
                try {
                    //TimableOutput.println(Thread.currentThread().getName() + " waiting for requests!!!!!!!!!!!!!!!!!!");
                    requestQueue.myWait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (requestQueue.hasSche()) {
                scheduleStart(requestQueue.pollScheRequest());
            }
            try {
                execute();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
