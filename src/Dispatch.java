import com.oocourse.elevator2.PersonRequest;
import com.oocourse.elevator2.TimableOutput;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

public class Dispatch implements Runnable {
    private final PriorityQueue<PersonRequest> unDispatchQueue =
        new PriorityQueue<>(11,
        Comparator.comparing(PersonRequest::getPriority).reversed());
    private final Map<PersonRequest, Integer> nowFloorMap = new HashMap<>();
    private Elevator[] elevators;
    private boolean isEnd = false;

    public Dispatch(Elevator[] elevators) {
        this.elevators = elevators;
    }

    public synchronized void setEnd() {
        isEnd = true;
        notifyAll();
    }

    public synchronized boolean isEnd() {
        notifyAll();
        return isEnd;
    }

    public synchronized void dispatchWait() throws InterruptedException {
        wait();
    }

    public synchronized void offer(PersonRequest pr, Boolean isRearrange, int nowFloor) {
        unDispatchQueue.offer(pr);
        nowFloorMap.put(pr, (isRearrange ? nowFloor : intOf(pr.getFromFloor())));
        notifyAll();
    }

    private void dispatch() {
        PersonRequest pr = unDispatchQueue.poll();
        if (pr == null) {
            return;
        }
        int nearest = 1;
        int fromFloor = nowFloorMap.get(pr);
        for (int i = 1; i <= 6; i++) {
            if (!elevators[i].isFull() &&
                (elevators[i].getCurFloor() - fromFloor) < elevators[nearest].getCurFloor() &&
                !elevators[i].isInSchedule()) {
                nearest = i;
            }
        }
        TimableOutput.println(
            String.format("RECEIVE-%d-%d", pr.getPersonId(), elevators[nearest].getId()));
        elevators[nearest].getRequestQueue().offer(pr);
    }

    private static int intOf(String floor) {
        if (floor.startsWith("B")) {
            return (-Integer.parseInt(floor.substring(1)));
        } else {
            return (Integer.parseInt(floor.substring(1)));
        }
    }

    public void run() {
        while (true) {
            if (isEnd() && unDispatchQueue.isEmpty()) {
                break;
            }
            if (!isEnd() && unDispatchQueue.isEmpty()) {
                try {
                    dispatchWait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            dispatch();
        }
    }
}
