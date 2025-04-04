import com.oocourse.elevator2.PersonRequest;
import com.oocourse.elevator2.Request;
import com.oocourse.elevator2.ScheRequest;

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

    public synchronized ScheRequest pollScheRequest() {
        ScheRequest sr = nowScheRequest;
        nowScheRequest = null;
        notifyAll();
        return sr;
    }

    public synchronized boolean hasSche() {
        notifyAll();
        return nowScheRequest != null;
    }

    public synchronized void nowScheEnd() {
        notifyAll();
        nowScheRequest = null;
    }

    public synchronized void offer(Request r) {
        if (r instanceof PersonRequest) {
            PersonRequest pr = (PersonRequest) r;
            personRequests.add(pr);
            if (requestMap.containsKey(intOf(pr.getFromFloor()))) {
                requestMap.get(intOf(pr.getFromFloor())).add(pr);
            } else {
                PriorityQueue<PersonRequest> prs = new PriorityQueue<>(
                    Comparator.comparing(PersonRequest::getPriority).reversed());
                prs.add(pr);
                requestMap.put(intOf(pr.getFromFloor()), prs);
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
        notifyAll();
        if (requestMap.containsKey(floor) && !requestMap.get(floor).isEmpty()) {
            personRequests.remove(requestMap.get(floor).peek());
            return requestMap.get(floor).poll();
        } else {
            return null;
        }
    }

    public synchronized int nextTargetFloor(int curFloor) {
        int nextFloor = curFloor;
        // 向上查找
        boolean upFound = false;
        for (int i = curFloor + 1; i <= MAX_FLOOR; i++) {
            if (getRequestsAt(i) != null && !getRequestsAt(i).isEmpty()) {
                nextFloor = i;
                upFound = true;
                break;
            }
        }
        // 向下查找
        for (int i = curFloor - 1; i >= MIN_FLOOR; i--) {
            if (getRequestsAt(i) != null && !getRequestsAt(i).isEmpty()) {
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
        notifyAll();
        return nextFloor;
    }

    public synchronized PriorityQueue<PersonRequest> getRequestsAt(int floor) {
        // probably return null
        notifyAll();
        return requestMap.get(floor);
    }

    public synchronized int getComprehensivePriorityAt(int floor) {
        int sum = 0;
        if (getRequestsAt(floor) == null || getRequestsAt(floor).isEmpty()) {
            notifyAll();
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
        notifyAll();
        return sum;
    }

    public synchronized void setEnd() {
        isEnd = true;
        notifyAll();
    }

    public synchronized boolean isEnd() {
        notifyAll();
        return isEnd;
    }

    public synchronized boolean isEmpty() {
        if (!personRequests.isEmpty()) {
            notifyAll();
            return false;
        }
        if (nowScheRequest != null) {
            notifyAll();
            return false;
        }
        for (PriorityQueue<PersonRequest> prs : requestMap.values()) {
            if (!prs.isEmpty()) {
                notifyAll();
                return false;
            }
        }
        notifyAll();
        return true;
    }

    private int intOf(String floor) {
        if (floor.startsWith("B")) {
            notifyAll();
            return (-Integer.parseInt(floor.substring(1)));
        } else {
            notifyAll();
            return (Integer.parseInt(floor.substring(1)));
        }
    }

}
