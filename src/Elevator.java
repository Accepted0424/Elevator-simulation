import com.oocourse.elevator3.PersonRequest;
import com.oocourse.elevator3.ScheRequest;
import com.oocourse.elevator3.TimableOutput;
import com.oocourse.elevator3.UpdateRequest;

import java.util.*;

public class Elevator implements Runnable {
    private final int id;
    private int curFloor = 1;
    private final RequestQueue requestQueue;
    private final Queue<PersonRequest> insideQueue;
    private static final int capacity = 6;
    private volatile boolean inSchedule = false;
    private final Object scheduleLock = new Object();
    private int targetScheFloor;
    private static final long defaultTimePerFloor = 400;
    private long timePerFloor = 400;
    private static long timeStop = 1000;
    private static final long minTimeOpen2Close = 400; // 400ms
    private final Dispatch dispatch;
    private final Elevator[] elevators;
    private boolean afterUpdate = false;
    private final Object updateLock = new Object();
    private int partnerElevatorId = 0;
    private int transferFloor = 0;
    private int LIMIT_MAX_FLOOR = 7;
    private int LIMIT_MIN_FLOOR = -4;

    public Elevator(int id, Dispatch dispatch, Elevator[] elevators) {
        this.id = id;
        this.dispatch = dispatch;
        this.requestQueue = new RequestQueue();
        this.elevators = elevators;
        this.insideQueue = new PriorityQueue<>(
                Comparator.comparing(PersonRequest::getPriority));
    }

    public boolean transferFloorIsFree() {
        return (elevators[partnerElevatorId].getCurFloor() != transferFloor);
    }

    public int getId() {
        return id;
    }

    public RequestQueue getRequestQueue() {
        return requestQueue;
    }

    public int getCurFloor() {
        return curFloor;
    }

    public void removeAllReceive() {
        while (!getRequestQueue().getRequestsQueue().isEmpty()) {
            dispatch.offer(getRequestQueue().poll(), false, false, 0);
        }
    }

    public void beforeUpdateBegin() {
        removeAllReceive();
        allPersonOut();
    }

    public boolean canArriveAt(int floor) {
        return floor >= LIMIT_MIN_FLOOR && floor <= LIMIT_MAX_FLOOR;
    }

    public boolean canArriveTargetOf(PersonRequest pr) {
        return intOf(pr.getToFloor()) >= LIMIT_MIN_FLOOR && intOf(pr.getToFloor()) <= LIMIT_MAX_FLOOR;
    }

    public boolean isAfterUpdate() {
        synchronized (updateLock) {
            return afterUpdate;
        }
    }

    public void updateStart(UpdateRequest ur) throws InterruptedException {
        synchronized (updateLock) {
            if (id == ur.getElevatorAId()) {
                curFloor = intOf(ur.getTransferFloor()) + 1;
                LIMIT_MIN_FLOOR = intOf(ur.getTransferFloor());
            } else {
                curFloor = intOf(ur.getTransferFloor()) - 1;
                LIMIT_MAX_FLOOR = intOf(ur.getTransferFloor());
            }
            transferFloor = intOf(ur.getTransferFloor());
            partnerElevatorId = (id == ur.getElevatorAId()) ? ur.getElevatorBId() : ur.getElevatorAId();
            timePerFloor = 200;
            Thread.sleep(1000);
            afterUpdate = true;
            updateLock.notifyAll();
        }
    }

    public synchronized void scheduleStart(ScheRequest sr) {
        synchronized (scheduleLock) {
            inSchedule = true;
            TimableOutput.println(String.format("SCHE-BEGIN-%d", id));
            timePerFloor = (long) (sr.getSpeed() * 1000);
            targetScheFloor = intOf(sr.getToFloor());
            removeAllReceive();
            scheduleLock.notifyAll();
        }
    }

    public synchronized void scheduleEnd() {
        synchronized (scheduleLock) {
            TimableOutput.println(String.format("SCHE-END-%d", id));
            inSchedule = false;
            requestQueue.scheEnd();
            dispatch.hasScheEnd();
            timePerFloor = defaultTimePerFloor;
            if (dispatch.allElevatorsBusy()) {
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
        //TimableOutput.println("OPEN condition: " + hasPersonOut() + hasPersonIn() + needRearrange() + LIMIT_MIN_FLOOR + LIMIT_MAX_FLOOR);
        if (hasPersonOut() || hasPersonIn() || needRearrange()) {
            return Status.OPEN;
        }
        if (insideQueue.isEmpty()) {
            if (requestQueue.isEmpty()) {
                return Status.WAIT;
            } else {
                return updateDirection();
            }
        } else {
            if (Double.compare(getInsideUpPri(), getInsideDownPri()) >= 0 && curFloor + 1 < LIMIT_MAX_FLOOR) {
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
            if (isAfterUpdate() && curFloor == transferFloor && !canArriveTargetOf(pr)) {
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
        //TimableOutput.println("status: " + status);
        switch (status) {
            case OPEN:
                if (isAfterUpdate() && curFloor == transferFloor && !insideQueue.isEmpty()) {
                    TimableOutput.println(String.format("OPEN-%s-%d", formatFloor(curFloor), id));
                    for (PersonRequest pr : insideQueue) {
                        if (!canArriveTargetOf(pr)) {
                            insideQueue.remove(pr);
                            TimableOutput.println(String.format("OUT-F-%d-%s-%d",
                                    pr.getPersonId(), formatFloor(curFloor), id));
                            dispatch.offer(pr, true, false, curFloor);
                        }
                    }
                    TimableOutput.println(String.format("CLOSE-%s-%d", formatFloor(curFloor), id));
                } else if (!inSchedule) {
                    TimableOutput.println(String.format("OPEN-%s-%d", formatFloor(curFloor), id));
                    personOut();
                    personIn();
                    rearrange();
                    Thread.sleep(minTimeOpen2Close);
                    TimableOutput.println(String.format("CLOSE-%s-%d", formatFloor(curFloor), id));
                }
                break;
            case MOVE:
                if (isAfterUpdate()) {
                    if (curFloor + 1 < LIMIT_MAX_FLOOR || (curFloor + 1 == LIMIT_MAX_FLOOR && transferFloorIsFree())) {
                        Thread.sleep(timePerFloor);
                        curFloor++;
                        if (curFloor == 0) {
                            curFloor++;
                        }
                        TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                    }
                } else {
                    Thread.sleep(timePerFloor);
                    curFloor++;
                    if (curFloor == 0) {
                        curFloor++;
                    }
                    TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                }
                break;
            case REVERSE:
                if (isAfterUpdate()) {
                    if (curFloor - 1 > LIMIT_MIN_FLOOR || (curFloor - 1 == LIMIT_MIN_FLOOR && transferFloorIsFree())) {
                        Thread.sleep(timePerFloor);
                        curFloor--;
                        if (curFloor == 0) {
                            curFloor--;
                        }
                        TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                    }
                } else {
                    Thread.sleep(timePerFloor);
                    curFloor--;
                    if (curFloor == 0) {
                        curFloor--;
                    }
                    TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                }
                break;
            case WAIT:
            default:
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
                dispatch.offer(insideQueue.poll(), true, false,curFloor);
                insideQueue.add(requestQueue.poll(curFloor));
            }
        }
    }

    private synchronized void personOut() throws InterruptedException {
        Iterator<PersonRequest> iterator = insideQueue.iterator();
        while (iterator.hasNext()) {
            PersonRequest pr = iterator.next();
            if (intOf(pr.getToFloor()) == curFloor) {
                dispatch.onePersonArrive();
                if (dispatch.allElevatorsBusy()) {
                    dispatch.hasFreeElevator();
                }
                iterator.remove();  // 安全删除
                TimableOutput.println(String.format("OUT-S-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
            }
        }
    }

    private synchronized void allPersonOut() {
        Iterator<PersonRequest> iterator = insideQueue.iterator();
        while (iterator.hasNext()) {
            PersonRequest pr = iterator.next();
            if (intOf(pr.getToFloor()) == curFloor) {
                iterator.remove();  // 安全删除
                TimableOutput.println(String.format("OUT-S-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
                dispatch.onePersonArrive();
                if (dispatch.allElevatorsBusy()) {
                    dispatch.hasFreeElevator();
                }
            } else {
                iterator.remove();  // 安全删除
                TimableOutput.println(String.format("OUT-F-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
                dispatch.offer(pr, true, false, curFloor);
            }

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
            //TimableOutput.println("debug: " + Thread.currentThread().getName() + " while flag");
            // 输入结束且没有未分配请求则该电梯的requestQueue会setEnd
            // 如果该电梯没有未执行的请求、电梯内没人，不在调度状态，则结束该电梯线程
            if (requestQueue.isEnd() && requestQueue.isEmpty() &&
                insideQueue.isEmpty() && !inSchedule) {
                return;
            }
            // requestQueue未结束（还有可能收到分配），并且电梯内没人，也不处于调度状态，此时电梯不能移动，必须处于等待状态
            while (!requestQueue.isEnd() && requestQueue.isEmpty() &&
                    insideQueue.isEmpty() && !inSchedule) {
                try {
                    requestQueue.myWait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            // 如果requestQueue中有调度请求，应该开始调度，然后移动电梯
            if (requestQueue.hasSche() && !inSchedule) {
                scheduleStart(requestQueue.getScheRequest());
            }
            try {
                execute();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
