import com.oocourse.elevator2.PersonRequest;
import com.oocourse.elevator2.Request;
import com.oocourse.elevator2.ScheRequest;
import com.oocourse.elevator2.TimableOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Comparator;

public class Dispatch implements Runnable {
    private final Elevator[] elevators;
    private volatile boolean inputIsEnd = false;
    private volatile boolean allElevatorsBusy = false;
    private final Object busyLock = new Object();
    private final Map<PersonRequest, Integer> nowFloorMap = new HashMap<>();
    private final Queue<ScheRequest> unDispatchSche = new LinkedList<>();
    private final PriorityQueue<PersonRequest> unDispatchQueue =
        new PriorityQueue<>(11,
        Comparator.comparing(PersonRequest::getPriority).reversed());
    private static int personRequestReceive = 0;
    private static int personRequestArrive = 0;

    public Dispatch(Elevator[] elevators) {
        this.elevators = elevators;
    }

    public boolean allElevatorsBusy() {
        return allElevatorsBusy;
    }

    public synchronized void onePersonArrive() {
        //TimableOutput.println("one person arrive");
        personRequestArrive++;
        notifyAll();
    }

    public synchronized void hasScheEnd() {
        notifyAll();
    }

    public synchronized boolean isEmpty() {
        //TimableOutput.println("dispatch isEmpty has lock");
        notifyAll();
        return unDispatchQueue.isEmpty() && unDispatchSche.isEmpty();
    }

    public synchronized void setInputIsEnd() {
        inputIsEnd = true;
        //TimableOutput.println("Dispatch is set end");
        notifyAll();
    }

    public synchronized boolean isEnd() {
        return inputIsEnd && personRequestArrive == personRequestReceive;
    }

    public synchronized void dispatchWait() throws InterruptedException {
        wait();
    }

    public void hasFreeElevator() {
        synchronized (busyLock) {
            //TimableOutput.println("has free elevator!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            allElevatorsBusy = false;
            busyLock.notifyAll();
        }
    }

    public synchronized void offer(Request r, Boolean isRearrange, int nowFloor) {
        if (r instanceof PersonRequest) {
            PersonRequest pr = (PersonRequest) r;
            unDispatchQueue.offer(pr);
            nowFloorMap.put(pr, (isRearrange ? nowFloor : intOf(pr.getFromFloor())));
            if (!isRearrange) {
                personRequestReceive++;
            }
        } else if (r instanceof ScheRequest) {
            ScheRequest sr = (ScheRequest) r;
            unDispatchSche.offer(sr);
        }
        //TimableOutput.println("Dispatch get request: " + r);
        notifyAll();
    }

    private synchronized void dispatch() throws InterruptedException {
        while (!unDispatchSche.isEmpty()) {
            ScheRequest sr = unDispatchSche.peek();
            elevators[sr.getElevatorId()].getRequestQueue().offer(unDispatchSche.poll());
        }
        // 分配给最近的空闲电梯
        if (!unDispatchQueue.isEmpty()) {
            PersonRequest pr = unDispatchQueue.peek();
            int target = 0;
            for (int i = 1; i <= 6; i++) {
                if (!elevators[i].getRequestQueue().hasSche() &&
                    elevators[i].getRequestQueue().getRequestsQueue().size() < 6) {
                    //TimableOutput.println("elevator_" + i + " is free");
                    if (target != 0) {
                        if (Math.abs(elevators[i].getCurFloor() - intOf(pr.getFromFloor())) <
                            Math.abs(elevators[target].getCurFloor() - intOf(pr.getFromFloor()))) {
                            target = i;
                        }
                    } else {
                        target = i;
                    }
                }
            }
            if (target == 0) {
                allElevatorsBusy = true;
                return;
            }
            TimableOutput.println(
                String.format("RECEIVE-%d-%d", pr.getPersonId(), elevators[target].getId()));
            elevators[target].getRequestQueue().offer(unDispatchQueue.poll());
        }
        notifyAll();
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
            // 输入未结束，还有可能获取请求
            while (isEmpty() && !isEnd()) {
                try {
                    //TimableOutput.println("Dispatch waiting");
                    dispatchWait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            // 所有电梯处于忙碌状态，但是还有未分配请求，等待空闲电梯
            while (allElevatorsBusy && !isEmpty()) {
                synchronized (busyLock) {
                    //TimableOutput.println("all elevators busy!!!!!!!!!!!!!!!!!!!!!!!!!!");
                    try {
                        busyLock.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            // 输入结束，且没有未分配队列，告知电梯的已分配队列不会再有来自dispatch的分配

            if (isEnd() && isEmpty()) {
                for (int i = 1; i <= 6; i++) {
                    elevators[i].getRequestQueue().setEnd();
                }
                break;
            }
            try {
                dispatch();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
