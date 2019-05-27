package com.bcilab.lonelyelderly;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class FallActivity extends AppCompatActivity {
    private static final String TAG = "FallActivity";
    private boolean mIsBound = false;
    private ConnectionService mConnectionService = null;

    private static TextView statusText;
    private static TextView accel;
    private static LineChart mChart;
    private Thread graphThread;
    private static boolean plotData = true;

    private static Kalman mKalmanPrev;      // Kalman filter t value
    private static Kalman mKalmanNext;      // Karlam filter t+1 value
    private static float mPrev, mNext;      // Variable to save previous Kalman filter value

    public static Queue queue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fall);
        statusText = (TextView) findViewById(R.id.statusText);
        accel = (TextView) findViewById(R.id.accel);
        queue = new com.bcilab.lonelyelderly.Queue(20);

        // Bind service
        mIsBound = bindService(new Intent(FallActivity.this, ConnectionService.class), mConnection, Context.BIND_AUTO_CREATE);

        // Kalman Filter Initialize
        mKalmanPrev = new Kalman(0.0f);
        mKalmanNext = new Kalman(0.0f);

        // Real-time Line Chart
        mChart = (LineChart) findViewById(R.id.chart1);
        mChart.getDescription().setEnabled(true);
        mChart.getDescription().setText("Real Time Falling Detection");
        mChart.setTouchEnabled(false);
        mChart.setDragEnabled(false);
        mChart.setScaleEnabled(false);
        mChart.setDrawGridBackground(true);
        mChart.setPinchZoom(false);
        mChart.setBackgroundColor(Color.WHITE);

        LineData data = new LineData();
        data.setValueTextColor(Color.WHITE);
        mChart.setData(data);
        startPlot();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (graphThread != null) {
            graphThread.interrupt();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        graphThread.interrupt();

        // Clean up connections
        if (mIsBound == true && mConnectionService != null) {
            if (mConnectionService.closeConnection() == false) {
                updateStatus("Disconnected");
            }
        }
        // Un-bind service
        if (mIsBound) {
            unbindService(mConnection);
            mIsBound = false;
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mConnectionService = ((ConnectionService.LocalBinder) service).getService();
            updateStatus("onServiceConnected");
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            mConnectionService = null;
            mIsBound = false;
            updateStatus("onServiceDisconnected");
        }
    };

    public void mOnClick(View v) {
        switch (v.getId()) {
            case R.id.buttonConnect: {
                if (mIsBound == true && mConnectionService != null) {
                    mConnectionService.findPeers();
                    accel.setText("측정중");
                    updateStatus("Connected");
                }
                break;
            }
            case R.id.buttonDisconnect: {
                if (mIsBound == true && mConnectionService != null) {
                    if (mConnectionService.closeConnection() == false) {
                        updateStatus("Disconnected");
                        Toast.makeText(getApplicationContext(), R.string.ConnectionAlreadyDisconnected, Toast.LENGTH_LONG).show();
                        accel.setText("미측정");
                        updateStatus("Disconnected");
                    }
                }
                break;
            }
            case R.id.buttonBack: {
                Intent intent = new Intent();
                setResult(RESULT_OK, intent);
                finish();
                break;
            }
        }
    }

    // 모니터링 상태 표시
    public static void updateStatus(final String str) {
        statusText.setText(str);
    }

    // 가속도 데이터 받아오기 및 그래프 그리기
    public static void updateAccel(final String str) {
        final String[] SVM = str.split("\\s");

        float Ivalue;                                      // next - prev
        float prev = 0.0f;                                 // variable to apply Kalman filter
        float next = 0.0f;

        if(plotData){
            for(int i = 1; i < 10; i++) {
//                int[] timestamp = new int[9];
//                float[] Ivalue = new float[9];             // next - prev
//                int original = Integer.parseInt(SVM[11]);

                prev = (float) mKalmanPrev.update(Float.parseFloat(SVM[i]));
                next = (float) mKalmanNext.update(Float.parseFloat(SVM[i+1]));
                Ivalue = next - prev;

                if(queue.isFull()) {
                    queue.Dequeue();
                    queue.Enqueue(Ivalue);
                } else
                    queue.Enqueue(Ivalue);

//                if(i == 1)                                  // 처음으로 받은 데이터의 시간
//                    saveFile(Ivalue[i], original);
//                else {                                      // 처음 이후 데이터는 시간 증가시키는 과정 추가
//                    timestamp[i] = original + (49*(i-1));
//                    saveFile(Ivalue[i], timestamp[i]);
//                }

                addEntry(Ivalue, 0);
                mPrev = prev;
                mNext = next;
            }
            plotData = false;
        }
    }

    private AlertDialog makeEmergencyDialog(){
        /* makeEmergencyDialog
         * 낙상 감지 알림상자
         * 제목 : 긴급
         * 내용 : 낙상 의심
         * 연락 버튼 누를 시 - 긴급연락처로 전화 걸기
         * 취소 버튼 누를 시 - 앱 종료
         */
        AlertDialog.Builder emergencyDialog;
        String str = getIntent().getStringExtra("phoneNum");
        final Uri uri = Uri.parse("tel:" + str);

        emergencyDialog = new AlertDialog.Builder(this);
        emergencyDialog.setTitle("긴급");
        emergencyDialog.setMessage("낙상 의심");

        emergencyDialog.setPositiveButton("연락", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Hard Coded : should be modified later

                Intent intent = new Intent(Intent.ACTION_CALL);
                intent.setData(uri);
                startActivity(intent);
            }
        });

        emergencyDialog.setNegativeButton("취소(앱 종료)", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // 앱 종료
                moveTaskToBack(true);
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });

        // To do list
        // 알림 상자 팝업 시 진동과 벨소리 기능 추가 - 진동 추가됨
        return emergencyDialog.show();
    }

    public static void saveFile(float filteredX, int accel_data){
        String state = Environment.getExternalStorageState();   // 외부저장소(SDcard)의 상태 얻어오기
        File path;                                              // 저장 데이터가 존재하는 디렉토리경로
        File file;                                              // 파일명까지 포함한 경로

        if(!state.equals(Environment.MEDIA_MOUNTED)){           // SDcard 의 상태가 쓰기 가능한 상태로 마운트되었는지 확인
            return;
        }

        path= Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        file= new File(path, "BandData.txt");            // 파일명까지 포함함 경로의 File 객체 생성
        try {                                                  // 데이터 추가가 가능한 파일 작성자(FileWriter 객체생성)
            FileWriter wr= new FileWriter(file,true);  // 두번째 파라미터 true: 기존파일에 내용 이어쓰기
            PrintWriter writer= new PrintWriter(wr);
            writer.println(filteredX+ " " + accel_data);
            writer.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    // LineChart startPlot()
    private void startPlot(){

        if(graphThread != null){
            graphThread.interrupt();
        }

        graphThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true){
                    plotData = true;
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e){
                        e.printStackTrace();
                    }
                }
            }
        });

        graphThread.start();
    }

    // LineChart addEntry()
    private static void addEntry(float idx, int order){
        LineData data = mChart.getData();

        if (data != null){
            ILineDataSet set = data.getDataSetByIndex(order);

            if(set == null){
                set = createSet(order);
                data.addDataSet(set);
            }

            data.addEntry(new Entry(set.getEntryCount(), idx), order);
            data.setDrawValues(false);
            data.notifyDataChanged();
        }

        // let the chart know it's data has changed
        mChart.notifyDataSetChanged();

        mChart.setMaxVisibleValueCount(220);
        mChart.moveViewToX(data.getEntryCount());
    }

    private final static int[] colors = new int[] {
            ColorTemplate.VORDIPLOM_COLORS[0],
            ColorTemplate.VORDIPLOM_COLORS[1],
            ColorTemplate.VORDIPLOM_COLORS[4]
    };

    // LineChart createSet()
    private static LineDataSet createSet(int order){
        // order 필요 없어짐 - 수정해야 함
        LineDataSet set;
        set = new LineDataSet(null, "SVM Variation");
        set.setColor(colors[1]);
        set.setLineWidth(2.5f);
        set.setDrawCircles(false);
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setMode(LineDataSet.Mode.LINEAR);
//        set.setCubicIntensity(0.2f);

        return set;
    }
}