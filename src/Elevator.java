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
    private UpdateRequest ur;
    private boolean updateHasBegin = false;
    private boolean afterUpdate = false;
    private final Object updateLock = new Object();
    private int partnerElevatorId = 0;
    private int transferFloor = 0;
    private int limitMaxFloor = 7;
    private int limitMinFloor = -4;
    private boolean transferFloorIsOccupied = false;
    private boolean hasAcceptUpdate;
    private boolean inUpdate;

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
        return !updateHasBegin || afterUpdate;
    }

    private boolean canMove() {
        if (updateHasBegin && !afterUpdate) {
            return false;
        }
        int nextFloor = curFloor == -1 ? 1 : curFloor + 1;
        if (nextFloor <= limitMaxFloor) {
            if (afterUpdate && nextFloor == transferFloor) {
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
        int nextFloor = curFloor == 1 ? -1 : curFloor - 1;
        if (nextFloor >= limitMinFloor) {
            if (afterUpdate && nextFloor == transferFloor) {
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

    private synchronized void modifyFloor(boolean add, boolean dec, boolean modify, int floor) {
        if (add) {
            curFloor++;
            if (curFloor == 0) {
                curFloor++;
            }
        } else if (dec) {
            curFloor--;
            if (curFloor == 0) {
                curFloor--;
            }
        } else if (modify) {
            curFloor = floor;
        }
    }

    public void updateParam() {
        if (id == ur.getElevatorAId()) {
            int modifiedFloor =  intOf(ur.getTransferFloor()) == -1 ?
                1 : intOf(ur.getTransferFloor()) + 1;
            modifyFloor(false, false, true, modifiedFloor);
            limitMinFloor = intOf(ur.getTransferFloor());
        } else {
            int modifiedFloor =  intOf(ur.getTransferFloor()) == 1 ?
                -1 : intOf(ur.getTransferFloor()) - 1;
            modifyFloor(false, false, true, modifiedFloor);
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

    public void updateDone() {
        synchronized (updateLock) {
            afterUpdate = true;
            hasAcceptUpdate = false;
            dispatch.hasFreeElevator();
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
            if ((afterUpdate && curFloor == transferFloor && !canArriveTargetOf(pr)) ||
                intOf(pr.getToFloor()) == curFloor) {
                return true;
            }
        }
        return false;
    }

    private synchronized boolean hasPersonInButFull() {
        return requestQueue.getRequestsAt(curFloor) != null && insideQueue.size() == capacity &&
                !requestQueue.getRequestsAt(curFloor).isEmpty();
    }

    private synchronized boolean hasPersonIn() {
        return requestQueue.getRequestsAt(curFloor) != null
                && !requestQueue.getRequestsAt(curFloor).isEmpty() && insideQueue.size() < capacity;
    }

    private Status updateDirection() {
        int nextFloor = requestQueue.nextTargetFloor(curFloor, this);
        return nextFloor > curFloor && canMove() ? Status.MOVE :
                nextFloor == curFloor ? Status.WAIT :
                canReverse() ? Status.REVERSE : Status.WAIT;
    }

    private final Object clearInsideLock = new Object();

    public void wait2clearInside() throws InterruptedException {
        synchronized (clearInsideLock) {
            while (!insideQueue.isEmpty() || !inUpdate) {
                clearInsideLock.wait();
            }
        }
    }

    public boolean canDispatch() {
        return (!updateHasBegin || afterUpdate) && !inUpdate && !hasAcceptUpdate;
    }

    public void insideHasClear() {
        synchronized (clearInsideLock) {
            clearInsideLock.notifyAll();
        }
    }

    public void acceptUpdate(UpdateRequest updateRequest) {
        ur = updateRequest;
        requestQueue.myNotify();
        hasAcceptUpdate = true;
    }

    private void executeUpdate() throws InterruptedException {
        inUpdate = true;
        updateHasBegin = true;
        if (!insideQueue.isEmpty()) {
            TimableOutput.println(String.format("OPEN-%s-%d", formatFloor(curFloor), id));
            Thread.sleep(minTimeOpen2Close);
            allPersonOut();
            TimableOutput.println(String.format("CLOSE-%s-%d", formatFloor(curFloor), id));
        }
        insideHasClear();
        hasAcceptUpdate = false;
        Thread.sleep(1000);
        updateParam();
        removeAllReceive();
        inUpdate = false;
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
            status = canMove() ? Status.MOVE : canReverse() ? Status.REVERSE : Status.WAIT;
        }
        switch (status) {
            case UPDATE:
                executeUpdate();
                break;
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
                if ((curFloor + 1 == 0 && curFloor + 2 == transferFloor) ||
                    curFloor + 1 == transferFloor) {
                    transferFloorIsOccupied = true;
                }
                modifyFloor(true, false, false, 0);
                Thread.sleep(timePerFloor);
                TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                if (curFloor != transferFloor) {
                    transferFloorIsOccupied = false;
                }
                break;
            case REVERSE:
                if ((curFloor - 1 == 0 && curFloor - 2 == transferFloor) ||
                    curFloor - 1 == transferFloor) {
                    transferFloorIsOccupied = true;
                }
                modifyFloor(false, true, false, 0);
                Thread.sleep(timePerFloor);
                TimableOutput.println(String.format("ARRIVE-%s-%d", formatFloor(curFloor), id));
                if (curFloor != transferFloor) {
                    transferFloorIsOccupied = false;
                }
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

    private synchronized void personOut() {
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
                dispatch.offer(pr, true, false, curFloor);
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
        return floor < 0 ? String.format("B%d", Math.abs(floor)) :
                String.format("F%d", Math.abs(floor));
    }

    private int intOf(String floor) {
        return floor.startsWith("B") ? (-Integer.parseInt(floor.substring(1))) :
                (Integer.parseInt(floor.substring(1)));
    }

    @Override
    public void run() {
        while (true) {
            if (requestQueue.isEnd() && requestQueue.isEmpty() &&
                insideQueue.isEmpty() && !inSchedule) {
                return;
            }
            while (!requestQueue.isEnd() && requestQueue.isEmpty() &&
                    insideQueue.isEmpty() && !inSchedule && !hasAcceptUpdate &&
                    (!afterUpdate || curFloor != transferFloor)) {
                try {
                    requestQueue.myWait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
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
