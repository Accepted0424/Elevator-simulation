import com.oocourse.elevator3.ElevatorInput;
import com.oocourse.elevator3.Request;
import com.oocourse.elevator3.TimableOutput;

public class MainClass {
    public static void main(String[] args) throws Exception {
        TimableOutput.initStartTimestamp();
        Elevator[] elevators = new Elevator[7];
        // 启动分配线程
        Dispatch dispatch = new Dispatch(elevators);
        new Thread(dispatch, "dispatch").start();

        // 启动六个电梯线程
        for (int i = 1; i <= 6; i++) {
            elevators[i] = new Elevator(i, dispatch, elevators);
            new Thread(elevators[i], "elevator_" + i).start();
        }

        // 将输入分配到对应电梯
        ElevatorInput elevatorInput = new ElevatorInput(System.in);
        while (true) {
            Request request = elevatorInput.nextRequest();
            if (request == null) {
                dispatch.setInputIsEnd();
                break;
            } else {
                dispatch.offer(request, false, true, 0);
            }
        }
        // 结束分配进程
        elevatorInput.close();
    }
}
