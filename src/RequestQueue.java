import com.oocourse.elevator3.PersonRequest;
import com.oocourse.elevator3.Request;
import com.oocourse.elevator3.ScheRequest;

import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

public class RequestQueue {
    private PriorityQueue<PersonRequest> personRequests;
    private HashMap<Integer, PriorityQueue<PersonRequest>> requestMap;
    private ScheRequest nowScheRequest;
    private boolean isEnd = false;
    private static final int MAX_FLOOR = 7;
    private static final int MIN_FLOOR = -4;

    public RequestQueue() {
        personRequests = new PriorityQueue<>(
                Comparator.comparing(PersonRequest::getPriority).reversed());
        requestMap = new HashMap<>();
    }

    public PriorityQueue<PersonRequest> getRequestsQueue() {
        return personRequests;
    }

    public synchronized void myWait() throws InterruptedException {
        wait();
    }

    public synchronized void myNotify() {
        notifyAll();
    }

    public synchronized ScheRequest getScheRequest() {
        return nowScheRequest;
    }

    public synchronized void scheEnd() {
        nowScheRequest = null;
    }

    public synchronized boolean hasSche() {
        return nowScheRequest != null;
    }

    public synchronized void offer(Request r, int nowFloor) {
        if (r instanceof PersonRequest) {
            PersonRequest pr = (PersonRequest) r;
            personRequests.add(pr);
            if (requestMap.containsKey(nowFloor)) {
                requestMap.get(nowFloor).add(pr);
            } else {
                PriorityQueue<PersonRequest> prs = new PriorityQueue<>(
                    Comparator.comparing(PersonRequest::getPriority).reversed());
                prs.add(pr);
                requestMap.put(nowFloor, prs);
            }
        } else if (r instanceof ScheRequest) {
            nowScheRequest = (ScheRequest) r;
        } else {
            System.err.println("Unknown request");
        }
        notifyAll();
    }

    public synchronized PersonRequest poll(int floor) {
        while (personRequests.isEmpty() && !isEnd) {
            try {
                wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (requestMap.containsKey(floor) && !requestMap.get(floor).isEmpty()) {
            personRequests.remove(requestMap.get(floor).peek());
            return requestMap.get(floor).poll();
        } else {
            return null;
        }
    }

    public synchronized PersonRequest poll() {
        PersonRequest pr = personRequests.peek();
        personRequests.remove(pr);
        for (int i = MIN_FLOOR; i <= MAX_FLOOR; i++) {
            if (requestMap.get(i) != null && !requestMap.get(i).isEmpty()) {
                requestMap.get(i).remove(pr);
            }
        }
        return pr;
    }

    public synchronized int nextTargetFloor(int curFloor, Elevator elevator) {
        int nextFloor = curFloor;
        // 向上查找
        boolean upFound = false;
        for (int i = curFloor + 1; i <= MAX_FLOOR; i++) {
            if (getRequestsAt(i) != null &&
                !getRequestsAt(i).isEmpty() && elevator.canArriveAt(i)) {
                nextFloor = i;
                upFound = true;
                break;
            }
        }
        // 向下查找
        for (int i = curFloor - 1; i >= MIN_FLOOR; i--) {
            if (getRequestsAt(i) != null &&
                !getRequestsAt(i).isEmpty() && elevator.canArriveAt(i)) {
                if (!upFound) {
                    nextFloor = i;
                    break;
                } else {
                    if (getComprehensivePriorityAt(i) > getComprehensivePriorityAt(nextFloor)) {
                        nextFloor = i;
                        break;
                    }
                }
            }
        }
        return nextFloor;
    }

    public synchronized PriorityQueue<PersonRequest> getRequestsAt(int floor) {
        // probably return null
        return requestMap.get(floor);
    }

    public synchronized int getComprehensivePriorityAt(int floor) {
        int sum = 0;
        if (getRequestsAt(floor) == null || getRequestsAt(floor).isEmpty()) {
            return 0;
        }
        for (PersonRequest pr : getRequestsAt(floor)) {
            if (intOf(pr.getToFloor()) > floor) {
                int floorDiff = intOf(pr.getToFloor()) > 0 && floor < 0 ?
                    intOf(pr.getToFloor()) - floor - 1 :
                    intOf(pr.getToFloor()) - floor;
                sum += pr.getPriority() * floorDiff;
            } else {
                int floorDiff = floor > 0 && intOf(pr.getToFloor()) < 0 ?
                    floor - intOf(pr.getToFloor()) - 1 :
                    floor - intOf(pr.getToFloor());
                sum += pr.getPriority() * floorDiff;
            }
        }
        return sum;
    }

    public synchronized void setEnd() {
        isEnd = true;
        notifyAll();
    }

    public synchronized boolean isEnd() {
        return isEnd;
    }

    public synchronized boolean isEmpty() {
        if (!personRequests.isEmpty()) {
            return false;
        }
        if (nowScheRequest != null) {
            return false;
        }
        for (PriorityQueue<PersonRequest> prs : requestMap.values()) {
            if (!prs.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int intOf(String floor) {
        if (floor.startsWith("B")) {
            return (-Integer.parseInt(floor.substring(1)));
        } else {
            return (Integer.parseInt(floor.substring(1)));
        }
    }

}
