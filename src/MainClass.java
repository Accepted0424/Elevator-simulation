import com.oocourse.elevator2.ElevatorInput;
import com.oocourse.elevator2.Request;
import com.oocourse.elevator2.TimableOutput;

public class MainClass {
    // debug info
    public static final boolean debug = false;
    public static final String RESET = "\u001B[0m";  // 重置颜色
    public static final String BLUE = "\u001B[34m";  // 蓝色

    public static void main(String[] args) throws Exception {
        TimableOutput.initStartTimestamp();
        Elevator[] elevators = new Elevator[7];

        // 启动分配线程
        Dispatch dispatch = new Dispatch(elevators);
        Thread dispatchThread = new Thread(dispatch, "dispatch");
        dispatchThread.setDaemon(true);
        dispatchThread.start();

        // 启动六个电梯线程
        for (int i = 1; i <= 6; i++) {
            elevators[i] = new Elevator(i, dispatch);
            new Thread(elevators[i], "elevator_" + i).start();
        }

        // 将输入分配到对应电梯
        ElevatorInput elevatorInput = new ElevatorInput(System.in);
        while (true) {
            Request request = elevatorInput.nextRequest();
            if (request == null) {
                //TimableOutput.println("Input end");
                dispatch.setEnd();
                break;
            } else {
                dispatch.offer(request, false, 0);
            }
        }
        // 结束分配进程
        elevatorInput.close();
    }
}

/*
SCHE-6-0.2-F1
417-PRI-15-FROM-B2-TO-B4
SCHE-3-0.4-B1
648-PRI-32-FROM-B2-TO-F1
 */
