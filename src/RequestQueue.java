import com.oocourse.elevator1.PersonRequest;

import java.util.ArrayList;
import java.util.HashMap;

public class RequestQueue {
    private ArrayList<PersonRequest> requests;
    private HashMap<Integer, ArrayList<PersonRequest>> requestMap;
    private boolean isEnd = false;
    private static final int MAX_FLOOR = 7;
    private static final int MIN_FLOOR = -4;

    public RequestQueue() {
        requests = new ArrayList<>();
        requestMap = new HashMap<>();
    }

    public synchronized void offer(PersonRequest pr) {
        requests.add(pr);
        if (requestMap.containsKey(intOf(pr.getFromFloor()))) {
            requestMap.get(intOf(pr.getFromFloor())).add(pr);
        } else {
            ArrayList<PersonRequest> prs = new ArrayList<>();
            prs.add(pr);
            requestMap.put(intOf(pr.getFromFloor()), prs);
        }
        notifyAll();
    }

    public synchronized PersonRequest poll(int floor) {
        while (requests.isEmpty() && !isEnd) {
            try {
                wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        notifyAll();
        if (requestMap.containsKey(floor) && !requestMap.get(floor).isEmpty()) {
            requests.remove(requestMap.get(floor).get(0));
            return requestMap.get(floor).remove(0);
        } else {
            return null;
        }
    }

    public synchronized int nextTargetFloor(int curFloor) {
        int nextFloor = curFloor;
        // 向上查找
        for (int i = curFloor; i <= MAX_FLOOR; i++) {
            if (getRequestsAt(i) != null && !getRequestsAt(i).isEmpty()) {
                nextFloor = i;
                break;
            }
        }
        // 向下查找
        for (int i = curFloor; i >= MIN_FLOOR; i--) {
            if (getRequestsAt(i) != null && !getRequestsAt(i).isEmpty()) {
                if (getComprehensivePriorityAt(i) > getComprehensivePriorityAt(nextFloor)) {
                    nextFloor = i;
                }
                break;
            }
        }
        return nextFloor;
    }

    public synchronized ArrayList<PersonRequest> getRequestsAt(int floor) {
        // probably return null
        return requestMap.get(floor);
    }

    public synchronized int getComprehensivePriorityAt(int floor) {
        /* Undone */
        int sum = 0;
        for (PersonRequest prs: getRequestsAt(floor)) {
            sum += prs.getPriority();
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
        if (requests.isEmpty()) {
            return true;
        }
        for (ArrayList<PersonRequest> prs : requestMap.values()) {
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
