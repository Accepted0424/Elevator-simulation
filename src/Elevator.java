import com.oocourse.elevator3.PersonRequest;
import com.oocourse.elevator3.ScheRequest;
import com.oocourse.elevator3.TimableOutput;
import com.oocourse.elevator3.UpdateRequest;

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
    private volatile boolean inSchedule = false;
    private final Object scheduleLock = new Object();
    private int targetScheFloor;
    private static final long defaultTimePerFloor = 400;
    private long timePerFloor = 400;
    private static long timeStop = 1000;
    private static final long minTimeOpen2Close = 400; // 400ms
    private final Dispatch dispatch;
    private final Elevator[] elevators;
    private boolean updateHasBegin = false;
    private boolean afterUpdate = false;
    private final Object updateLock = new Object();
    private final Object stillLock = new Object();
    private boolean isStill = true;
    private int partnerElevatorId = 0;
    private int transferFloor = 0;
    private int limitMaxFloor = 7;
    private int limitMinFloor = -4;
    private boolean transferFloorIsOccupied = false;

    public Elevator(int id, Dispatch dispatch, Elevator[] elevators) {
        this.id = id;
        this.dispatch = dispatch;
        this.requestQueue = new RequestQueue();
        this.elevators = elevators;
        this.insideQueue = new PriorityQueue<>(
                Comparator.comparing(PersonRequest::getPriority));
    }

    public boolean transferFloorIsFree() {
        return !elevators[partnerElevatorId].getTransferFloorIsOccupied();
    }

    public boolean getTransferFloorIsOccupied() {
        return transferFloorIsOccupied;
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

    private boolean canOpen() {
        if (updateHasBegin && !afterUpdate) {
            return false;
        }
        return true;
    }

    private boolean canMove() {
        //TimableOutput.println(updateHasBegin + " " + afterUpdate);
        if (updateHasBegin && !afterUpdate) {
            return false;
        }
        if (curFloor + 1 <= limitMaxFloor) {
            if (afterUpdate && curFloor + 1 == transferFloor) {
                return transferFloorIsFree();
            }
            return true;
        }
        return false;
    }

    private boolean canReverse() {
        if (updateHasBegin && !afterUpdate) {
            return false;
        }
        if (curFloor - 1 >= limitMinFloor) {
            if (afterUpdate && curFloor - 1 == transferFloor) {
                return transferFloorIsFree();
            }
            return true;
        }
        return false;
    }

    public void removeAllReceive() {
        while (!getRequestQueue().getRequestsQueue().isEmpty()) {
            dispatch.offer(getRequestQueue().poll(), false, false, 0);
        }
    }

    public void beforeUpdateBegin(UpdateRequest ur) {
        if (id == ur.getElevatorAId()) {
            curFloor = intOf(ur.getTransferFloor()) + 1;
            limitMinFloor = intOf(ur.getTransferFloor());
        } else {
            curFloor = intOf(ur.getTransferFloor()) - 1;
            limitMaxFloor = intOf(ur.getTransferFloor());
        }
        transferFloor = intOf(ur.getTransferFloor());
        partnerElevatorId = (id == ur.getElevatorAId()) ? ur.getElevatorBId() : ur.getElevatorAId();
        timePerFloor = 200;
    }

    public boolean canArriveAt(int floor) {
        return floor >= limitMinFloor && floor <= limitMaxFloor;
    }

    public boolean canArriveTargetOf(PersonRequest pr) {
        return intOf(pr.getToFloor()) >= limitMinFloor && intOf(pr.getToFloor()) <= limitMaxFloor;
    }

    public boolean updateHasBegin() {
        return updateHasBegin;
    }

    public void updateDone() {
        synchronized (updateLock) {
            afterUpdate = true;
            dispatch.hasFreeElevator();
            hasAcceptUpdate = false;
            updateLock.notifyAll();
        }
    }

    private void setStill() {
        synchronized (stillLock) {
            isStill = true;
            stillLock.notifyAll();
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

    private boolean inUpdate;

    private synchronized Status update() {
        /* LOOK */
        // 处于调度状态
        if (inSchedule) {
            if (targetScheFloor > curFloor && canMove()) {
                return Status.MOVE;
            } else if (canReverse()) {
                return Status.REVERSE;
            }
        }

        if (hasAcceptUpdate) {
            return Status.UPDATE;
        }

        // 处于非调度状态
        if (hasPersonOut() || hasPersonIn() || needRearrange()) {
            if (canOpen()) {
                return Status.OPEN;
            } else {
                return Status.WAIT;
            }
        }
        if (insideQueue.isEmpty()) {
            if (requestQueue.isEmpty()) {
                if (curFloor == transferFloor) {
                    if (canMove()) {
                        return Status.MOVE;
                    } else if (canReverse()) {
                        return Status.REVERSE;
                    }
                }
                return Status.WAIT;
            } else {
                return updateDirection();
            }
        } else {
            if (Double.compare(getInsideUpPri(), getInsideDownPri()) >= 0 && canMove()) {
                return Status.MOVE;
            } else if (canReverse()) {
                return Status.REVERSE;
            } else {
                if (curFloor == transferFloor) {
                    if (canMove()) {
                        return Status.MOVE;
                    } else if (canReverse()) {
                        return Status.REVERSE;
                    }
                }
                return Status.WAIT;
            }
        }
    }

    private synchronized boolean hasPersonOut() {
        for (PersonRequest pr : insideQueue) {
            if (intOf(pr.getToFloor()) == curFloor) {
                return true;
            }
            if (afterUpdate && curFloor == transferFloor && !canArriveTargetOf(pr)) {
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
        int nextFloor = requestQueue.nextTargetFloor(curFloor, this);
        if (nextFloor > curFloor && canMove()) {
            return Status.MOVE;
        } else if (nextFloor == curFloor) {
            return Status.WAIT;
        } else if (canReverse()) {
            return Status.REVERSE;
        } else {
            return Status.WAIT;
        }
    }

    private final Object clearInsideLock = new Object();

    public void wait2clearInside() throws InterruptedException {
        synchronized (clearInsideLock) {
            while (!insideQueue.isEmpty() || !inUpdate) {
                //TimableOutput.println(Thread.currentThread().getName() + ": wait2clearInside");
                clearInsideLock.wait();
            }
        }
    }

    public boolean canDispatch() {
        return !updateHasBegin || afterUpdate;
    }

    public void insideHasClear() {
        synchronized (clearInsideLock) {
            clearInsideLock.notifyAll();
        }
    }

    private boolean hasAcceptUpdate;

    public void acceptUpdate() {
        requestQueue.myNotify();
        hasAcceptUpdate = true;
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
        if (status == Status.WAIT && curFloor == transferFloor && afterUpdate) {
            if (canMove()) {
                status = Status.MOVE;
            } else if (canReverse()) {
                status = Status.REVERSE;
            }
        }
        //TimableOutput.println(Thread.currentThread().getName() + " status: " + status);
        switch (status) {
            case UPDATE:
                inUpdate = true;
                //TimableOutput.println(Thread.currentThread().getName() + " in UPDATE status");
                updateHasBegin = true;
                if (!insideQueue.isEmpty()) {
                    TimableOutput.println(String.format("OPEN-%s-%d", formatFloor(curFloor), id));
                    allPersonOut();
                    Thread.sleep(timeStop);
                    TimableOutput.println(String.format("CLOSE-%s-%d", formatFloor(curFloor), id));
                }
                //TimableOutput.println(Thread.currentThread().getName() + " will notify");
                insideHasClear();
                hasAcceptUpdate = false;
                //TimableOutput.println(Thread.currentThread().getName() + " will sleep");
                Thread.sleep(1000);
                removeAllReceive();
                inUpdate = false;
                return;
            case OPEN:
                isStill = false;
                if (!inSchedule) {
                    TimableOutput.println(String.format("OPEN-%s-%d", formatFloor(curFloor), id));
                    personOut();
                    personIn();
                    rearrange();
                    Thread.sleep(minTimeOpen2Close);
                    TimableOutput.println(String.format("CLOSE-%s-%d", formatFloor(curFloor), id));
                }
                setStill();
                break;
            case MOVE:
                isStill = false;
                if (curFloor + 1 == transferFloor) {
                    transferFloorIsOccupied = true;
                }
                curFloor++;
                if (curFloor == 0) {
                    curFloor++;
                }
                Thread.sleep(timePerFloor);
                TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                if (curFloor != transferFloor) {
                    transferFloorIsOccupied = false;
                }
                setStill();
                break;
            case REVERSE:
                isStill = false;
                if (curFloor - 1 == transferFloor) {
                    transferFloorIsOccupied = true;
                }
                curFloor--;
                if (curFloor == 0) {
                    curFloor--;
                }
                Thread.sleep(timePerFloor);
                TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                if (curFloor != transferFloor) {
                    transferFloorIsOccupied = false;
                }
                setStill();
                break;
            case WAIT:
                setStill();
                break;
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
                iterator.remove();  // 安全删除
                if (dispatch.allElevatorsBusy()) {
                    dispatch.hasFreeElevator();
                }
                TimableOutput.println(String.format("OUT-S-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
            } else if (afterUpdate && curFloor == transferFloor && !canArriveTargetOf(pr)) {
                iterator.remove();  // 安全删除
                if (dispatch.allElevatorsBusy()) {
                    dispatch.hasFreeElevator();
                }
                TimableOutput.println(String.format("OUT-F-%d-%s-%d",
                    pr.getPersonId(), formatFloor(curFloor), id));
                dispatch.offer(pr, true, false,curFloor);
            }
        }
    }

    private synchronized void allPersonOut() {
        Iterator<PersonRequest> iterator = insideQueue.iterator();
        while (iterator.hasNext()) {
            PersonRequest pr = iterator.next();
            //TimableOutput.println(pr.getToFloor() + curFloor);
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
                //TimableOutput.println(Thread.currentThread().getName() + " is end");
                return;
            }
            // requestQueue未结束（还有可能收到分配），并且电梯内没人，也不处于调度状态，此时电梯不能移动，必须处于等待状态
            while (!requestQueue.isEnd() && requestQueue.isEmpty() &&
                    insideQueue.isEmpty() && !inSchedule && !hasAcceptUpdate) {
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
