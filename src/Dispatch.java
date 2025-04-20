import com.oocourse.elevator3.PersonRequest;
import com.oocourse.elevator3.UpdateRequest;
import com.oocourse.elevator3.ScheRequest;
import com.oocourse.elevator3.Request;
import com.oocourse.elevator3.TimableOutput;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Dispatch implements Runnable {
    private final Elevator[] elevators;
    private volatile boolean inputIsEnd = false;
    private volatile boolean allElevatorsBusy = false;
    private final Object busyLock = new Object();
    private final Map<PersonRequest, Integer> nowFloorMap = new HashMap<>();
    private final Queue<ScheRequest> unDispatchSche = new LinkedList<>();
    private final Queue<UpdateRequest> unDispatchUpdate = new LinkedList<>();
    private final PriorityQueue<PersonRequest> unDispatchQueue =
        new PriorityQueue<>(11,
        Comparator.comparing(PersonRequest::getPriority).reversed());
    private static int personRequestReceive = 0;
    private static int personRequestArrive = 0;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private List<Future<?>> futures = new ArrayList<>();

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
        return unDispatchQueue.isEmpty() && unDispatchSche.isEmpty() && unDispatchUpdate.isEmpty();
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

    public synchronized void offer(Request r, Boolean isRearrange, Boolean first, int nowFloor) {
        if (r instanceof PersonRequest) {
            PersonRequest pr = (PersonRequest) r;
            unDispatchQueue.offer(pr);
            if (first) {
                nowFloorMap.put(pr, intOf(pr.getFromFloor()));
            }
            if (isRearrange) {
                //TimableOutput.println(pr + " nowFloor is reset to " + nowFloor);
                nowFloorMap.put(pr, nowFloor);
            }
            if (!isRearrange && first) {
                personRequestReceive++;
            }
        } else if (r instanceof ScheRequest) {
            ScheRequest sr = (ScheRequest) r;
            unDispatchSche.offer(sr);
        } else if (r instanceof UpdateRequest) {
            UpdateRequest ur = (UpdateRequest) r;
            try {
                elevators[ur.getElevatorAId()].acceptUpdate(ur);
                elevators[ur.getElevatorBId()].acceptUpdate(ur);
            } catch (Exception e) {
                e.printStackTrace();
            }
            unDispatchUpdate.add(ur);
            hasFreeElevator();
        }
        notifyAll();
    }

    private synchronized void dispatch() throws InterruptedException {
        while (!unDispatchSche.isEmpty()) {
            ScheRequest sr = unDispatchSche.peek();
            elevators[sr.getElevatorId()].getRequestQueue().offer(unDispatchSche.poll(),  0);
        }
        while (!unDispatchUpdate.isEmpty()) {
            UpdateRequest ur = unDispatchUpdate.poll();
            Future<?> future = executor.submit(() -> {
                try {
                    elevators[ur.getElevatorAId()].wait2clearInside();
                    elevators[ur.getElevatorBId()].wait2clearInside();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                TimableOutput.println(String.format("UPDATE-BEGIN-%d-%d",
                    ur.getElevatorAId(), ur.getElevatorBId()));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                TimableOutput.println(String.format("UPDATE-END-%d-%d",
                    ur.getElevatorAId(), ur.getElevatorBId()));
                elevators[ur.getElevatorAId()].updateDone();
                elevators[ur.getElevatorBId()].updateDone();
            });
            futures.add(future);
        }
        // 分配给最近的空闲电梯
        if (!unDispatchQueue.isEmpty()) {
            PersonRequest pr = unDispatchQueue.peek();
            int target1 = 0;
            for (int i = 1; i <= 6; i++) {
                if (!elevators[i].getRequestQueue().hasSche() &&
                    elevators[i].getRequestQueue().getRequestsQueue().size() < 10 &&
                    elevators[i].canDispatch() && elevators[i].canArriveAt(nowFloorMap.get(pr))) {
                    if (target1 != 0) {
                        if (Math.abs(elevators[i].getCurFloor() - intOf(pr.getFromFloor())) <
                            Math.abs(elevators[target1].getCurFloor() - intOf(pr.getFromFloor()))) {
                            target1 = i;
                        }
                    } else {
                        target1 = i;
                    }
                }
            }
            int target2 = searchTarget2(pr);
            if (target1 == 0 && target2 == 0) {
                allElevatorsBusy = true;
                return;
            }
            int target = target2 == 0 ? target1 : target2;
            TimableOutput.println(
                String.format("RECEIVE-%d-%d", pr.getPersonId(), elevators[target].getId()));
            elevators[target].getRequestQueue().offer(unDispatchQueue.poll(), nowFloorMap.get(pr));
        }
        notifyAll();
    }

    private int searchTarget2(PersonRequest pr) {
        int target2 = 0;
        for (int i = 1; i <= 6; i++) {
            if (!elevators[i].getRequestQueue().hasSche() &&
                elevators[i].getRequestQueue().getRequestsQueue().size() < 10 &&
                elevators[i].canDispatch() &&
                elevators[i].canArriveAt(nowFloorMap.get(pr)) &&
                elevators[i].canArriveTargetOf(pr)) {
                if (target2 != 0) {
                    target2 = i;
                } else {
                    target2 = i;
                    break;
                }
            }
        }
        return target2;
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
            // TimableOutput.println(personRequestArrive + " " + personRequestReceive);
            // 输入未结束，还有可能获取请求
            while (isEmpty() && !isEnd()) {
                try {
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
                //TimableOutput.println("begin fget");
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                //TimableOutput.println("end fget");
                executor.shutdown();
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
