import com.oocourse.elevator1.*;

public class MainClass {
    public static void main(String[] args) throws Exception {
        TimableOutput.initStartTimestamp();
        Elevator[] elevators = new Elevator[7];

        // 启动六个电梯线程
        for (int i = 1; i <= 6; i++) {
            elevators[i] = new Elevator(i);
            new Thread(elevators[i], "elevator_" + i).start();
        }

        // 将输入分配到对应电梯
        ElevatorInput elevatorInput = new ElevatorInput(System.in);
        while (true) {
            Request request = elevatorInput.nextRequest();
            if (request == null) {
                // 结束所有电梯进程
                for (int i = 1; i <= 6; i++) {
                    elevators[i].getRequestQueue().setEnd();
                }
                break;
            } else {
                if (request instanceof PersonRequest) {
                    PersonRequest pr = (PersonRequest) request;
                    // TimableOutput.println("Receive: " + pr);
                    elevators[pr.getElevatorId()].getRequestQueue().offer(pr);
                }
            }
        }
    }
}

/*
889-PRI-20-FROM-F2-TO-F6-BY-1
295-PRI-20-FROM-F1-TO-F5-BY-4
 */
