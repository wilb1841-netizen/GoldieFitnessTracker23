package com.example.goldiefitnesstracker2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.goldiefitnesstracker2.ui.theme.GoldieFitnessTracker2Theme
//Class 1
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.icu.lang.UCharacter.getDirection
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.sqrt
// Class 2
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.blue
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.components.XAxis
// Class 3
import android.Manifest
import android.R.attr.text
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.ButtonDefaults
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
// class 4
import android.widget.Toast
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.focusModifier
import com.example.goldiefitnesstracker2.Data.AppDatabase
import com.example.goldiefitnesstracker2.Data.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Collections.emptyList
import java.util.Date
import java.util.Locale




@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity(), SensorEventListener {

    // Compose state - read by FitnessTrackerScreen(), written from sensor callbacks
    private var motionText by mutableStateOf("Motion: Stationary")
    private var gyroText by mutableStateOf("Rotation (Pitch, Roll, Yaw): 0.00, 0.00, 0.00")
    private var directionText by mutableStateOf("Direction:")

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null


    // Sensor fusion - both needed for the compass (getRotationMatrix wants 3 values)
    private var accelValues = FloatArray(3)
    private var magnetValues = FloatArray(3)

    // Baseline - cancels gravity
    private var baselineX = 0f
    private var baselineY = 0f
    private var baselineZ = 9.8f

    // MPAndroidChart is a view, not a composable
    private lateinit var barChart: BarChart

    // Chart data: 8 buckets of 3 hours each (must be a val FloatArray we can mutate)
    private val hourlyStep = floatArrayOf(1000f, 1200f, 1800f, 0f, 1000f, 1200f, 1800f, 800f)

    // Hardware step counter
    private var stepCounterSensor: Sensor? = null // demo mode for emulator

    // Step tracking
    private var sessionStepCount = 0
    private var initialStepCount = -1 // anchor point (hardware counter is total since boot)

    // Health metric display state
    private var stepCountText by mutableStateOf("Steps: 0")
    private var caloriesText by mutableStateOf("Calories: 0.0 kcal")
    private var distanceText by mutableStateOf("Distance: 0.0 km")
    private val ACTIVITY_PERMISSION_CODE = 100


    // Demo Mode - Compose state so the button recomposes when it flips
    private var isDemoMode by mutableStateOf(false)
    private val demoHandler = Handler(Looper.getMainLooper())

    // ROOM
    private lateinit var database: AppDatabase
    private var sessionHistory by mutableStateOf<List<SessionEntity>>(emptyList())

    // Calibration
    private var isCalibrating = false
    private val calibrationSamples = mutableListOf<FloatArray>()

    // accuracy warning
    private var accuracyText by mutableStateOf("")


    companion object {
        private const val STATIONARY_THRESHOLD = 2.8f
        private const val WALKING_THRESHOLD = 12.8f
        private const val CALORIES_PER_STEP = 0.04f
        private const val STRIDE_LENGTH_M = 0.000762f
        private const val ACTIVITY_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSensors()
        requestActivityPermission()

        database = AppDatabase.getInstance(this)
        loadHistory()

        setContent {
            GoldieFitnessTracker2Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FitnessTrackerScreen(
                        motionText = motionText,
                        gyroText = gyroText,
                        directionText = directionText,
                        onRefreshChartClick = { setupChart() },
                        onChartCreated = { chart -> barChart = chart; setupChart() },
                        stepCountText = stepCountText,
                        caloriesText = caloriesText,
                        distanceText = distanceText,
                        isDemoMode = isDemoMode,
                        onDemoModeClick = { toggleDemoMode() },
                        accuracyText = accuracyText,
                        isCalibrating = isCalibrating,
                        onCalibrateClick = { startCalibration() },
                        sessionHistory = sessionHistory,
                        onSaveSessionClick = { saveSession() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        gyroscope?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        magnetometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        stepCounterSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        sensorManager.unregisterListener(this) // safety net - onPause already dose this
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    private fun requestActivityPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        )
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), ACTIVITY_PERMISSION_CODE)





    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelValues = event.values.clone()
            }
            // Assignment 1 -- gyroscope passes raw rad/s values to the display handler
            Sensor.TYPE_GYROSCOPE -> {
                handleGyroscope(event.values)
            }

            // Assignment 1 -- magnetometer must be cloned, then triggers compass update
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetValues = event.values.clone()
                updateCompass()
            }

            Sensor.TYPE_STEP_COUNTER -> {
                handleStepCounter(event.values[0].toInt())

            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        accuracyText = if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
        )
            "${sensor.name} accuracy LOW - please recalibrate"
        else ""
    }

    private fun handleAccelerometer(values: FloatArray) {
        val x = values[0] - baselineX
        val y = values[1] - baselineY
        val z = values[2] - baselineZ
        val magnitude = sqrt(x * x + y * y + z * z)
        motionText = "Motion: ${classifyMotion(magnitude)}"
        if (isCalibrating) {
            calibrationSamples.add(floatArrayOf(values[0], values[1], values[2]))
            if (calibrationSamples.size >= 20) finishCalibration()
        }
    }


    // values[] are rotation rates in radians/second
    private fun handleGyroscope(values: FloatArray) {
        gyroText =
            "Rotation (Pitch, Roll, Yaw): ${"%.2f".format(values[0])}, ${"%.2f".format(values[1])}, ${
                "%.2f".format(values[2])
            }"
    }

    // Sensor fusion: accel gives gravity (tilt), magnet gives North.
    // getRotationMatrix() returns false until both sensors have fired.
    private fun updateCompass() {
        val rm = FloatArray(9)
        val im = FloatArray(9)
        if (SensorManager.getRotationMatrix(rm, im, accelValues, magnetValues)) {
            val orientation = FloatArray(3); SensorManager.getOrientation(rm, orientation)
            val deg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
            directionText = "Direction: ${getDirection(deg)}"
        }
    }

    private fun getDirection(deg: Float): String = when {
        deg < 45f || deg >= 315f -> "North" // 315-359 and 0-44
        deg < 135f -> "East"                // 45-134
        deg < 225f -> "South"               // 135-224
        else -> "West"                      // 225-314
    }

    // Builds the three-layer data model (BarEntry -> BarDataSet -> BarData) and configures the chart
    // Called once when the chart is created, and again every time Refresh Charts is tapped
    private fun setupChart() {
        val entries = hourlyStep.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }
        val dataSet = BarDataSet(entries, "Steps").apply {
            color = Color.CYAN  // built-in constant, already an Int
            valueTextSize = 10f
        }
        barChart.apply {
            data = BarData(dataSet)
            description.text = "Step Count per Hour"
            xAxis.position = XAxis.XAxisPosition.BOTTOM  // labels below bars
            xAxis.granularity = 1f                       // one label per bar
            axisRight.isEnabled = false                  // hide duplicate right Y axis
            setFitBars(true)  // bars fill full chart width
            animateY(800)     // bars grow upward over 800ms on load
            invalidate()      // force redraw -- required after every data change
        }
    }


    // Hardware counter = total steps since boot, so subtract the first reading
    private fun handleStepCounter(totalSteps: Int) {
        if (initialStepCount == -1) initialStepCount = totalSteps
        sessionStepCount = totalSteps - initialStepCount
        updateHealthMetrics()
        updateChartWithSteps()
    }

    private fun updateHealthMetrics() {
        stepCountText = "Steps: $sessionStepCount"
        caloriesText = "Calories: ${"%.1f".format(sessionStepCount * CALORIES_PER_STEP)} kcal"
        distanceText = "Distance: ${"%.2f".format(sessionStepCount * STRIDE_LENGTH_M)} km"
    }

    private fun calculateCalories(steps: Int): Float {
        return (steps * CALORIES_PER_STEP)
    }

    private fun calculateDistance(steps: Int): Float {
        return (steps * STRIDE_LENGTH_M) /1000f

    }
    private fun updateChartWithSteps() {
        val updatedSteps = floatArrayOf(
            sessionStepCount * 0.1f,
            sessionStepCount * 0.2f,
            sessionStepCount * 0.3f,
            sessionStepCount * 0.4f
        )

        val entries = updatedSteps.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }
        val dataSet = BarDataSet(entries, "Steps").apply {
            color = Color.CYAN.toInt()
            valueTextSize = 10f
        }
        barChart.apply {
            data = BarData(dataSet)
            invalidate()
        }
    }

    private fun toggleDemoMode() {
        isDemoMode = !isDemoMode
        if (isDemoMode) {
            demoHandler.post(demoRunnable)
        } else {
            demoHandler.removeCallbacks(demoRunnable)
        }
    }

    private val demoRunnable = object : Runnable {
        private var compassAngle = 0f // tracks simulated heading independently
        override fun run() {
            if (!isDemoMode) return // safety check

            // sine wave walking simulation - feeds into handleAccelerometer(), same
            // method real sensor events call. the app cant tell the difference.
            val t = System.currentTimeMillis()
            handleAccelerometer(floatArrayOf(
                (Math.sin(t / 200.0) * 3).toFloat(),
                (Math.cos(t/300.0) * 2).toFloat(),
                9.8f + (Math.sin(t / 150.0) *4).toFloat()
            ))

            // Rotate compass 5 deg per tick so direction cycles through
            compassAngle = (compassAngle + 5f) % 360f
            directionText = "Direction: ${getDirection(compassAngle)}"
            sessionStepCount += 2 // simulate 4 steps/second at 500 ms interval
            updateHealthMetrics()
            updateChartWithSteps()
            demoHandler.postDelayed(this, 500)
        }
    }


    private fun loadHistory() {
        CoroutineScope(Dispatchers.IO).launch {
            val sessions = database.sessionDao().getRecentSessions()
            withContext(Dispatchers.Main) { sessionHistory = sessions }
        }
    }

    private fun startCalibration() {
        calibrationSamples.clear()  // discard any previous samples
        isCalibrating = true        // handleAccelerometer() checks this flag; also disables the button
    }

    private fun finishCalibration() {
        isCalibrating = false
        baselineX = calibrationSamples.map { it[0] }.average().toFloat()
        baselineY = calibrationSamples.map { it[1] }.average().toFloat()
        baselineZ = calibrationSamples.map { it[2] }.average().toFloat()
        calibrationSamples.clear()
    }

    private fun saveSession() {
        // bail out if there's nothing worth saving
        if (sessionStepCount == 0) {
            Toast.makeText(this, "No steps - please walk around", Toast.LENGTH_SHORT).show()
            return
        }
        val session = SessionEntity(
            timestamp = System.currentTimeMillis(), // right now, in milliseconds
            steps = sessionStepCount,               // current step count for this session
            calories = calculateCalories(sessionStepCount),
            distanceKm = calculateDistance(sessionStepCount)
        )
        CoroutineScope(Dispatchers.IO).launch {

        }
        // ASSIGNMENT 4 -- Implement this! See Hour 3 for the full spec.
        //   Launch a coroutine on Dispatchers.IO and insert `session` into the
        //   database via database.sessionDao(). Database calls are not allowed
        //   on the main thread. Once the insert finishes, switch back to
        //   Dispatchers.Main (withContext) to show a confirmation Toast and
        //   call loadHistory() again so the new session appears in the list.
    }
    private fun classifyMotion(magnitude: Float): String {
        return when {
            magnitude < STATIONARY_THRESHOLD -> "Stationary"
            magnitude < WALKING_THRESHOLD    -> "Walking"
            else                             -> "Jogging"
        }
    }
}

@Composable
fun FitnessTrackerScreen(
    motionText: String,
    gyroText: String,
    directionText: String,
    onRefreshChartClick: () -> Unit,
    onChartCreated: (BarChart) -> Unit,
    stepCountText: String,
    caloriesText: String,
    distanceText: String,
    isDemoMode: Boolean,
    onDemoModeClick: () -> Unit,
    accuracyText: String,
    isCalibrating: Boolean,
    onCalibrateClick: () -> Unit,
    sessionHistory:  List<SessionEntity>,
    onSaveSessionClick: () -> Unit
    ) {
    val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(text = motionText, fontSize = 20.sp, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = gyroText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = directionText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))

        // Accuracy warning - hidden until onAccuracyChanged fires
        if (accuracyText.isNotEmpty()) {
            Text(text = accuracyText, fontSize = 14.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        }

        // Calibration button
        Button(onClick = onCalibrateClick, enabled = !isCalibrating, modifier = Modifier.padding(bottom = 20.dp)) {
            Text(if (isCalibrating) "Calibrating..." else "Calibrate")
        }

        AndroidView(
            factory = { ctx -> BarChart(ctx).also(onChartCreated) },
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(bottom = 16.dp)
        )
        Button(onClick = onRefreshChartClick, modifier = Modifier.padding(bottom = 20.dp).align(Alignment.CenterHorizontally)) {
            Text("Refresh Charts")
        }

        // health metrics
        Text(text = stepCountText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text(text = caloriesText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text(text = distanceText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))

        Button(
            onClick = onDemoModeClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDemoMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.padding(bottom = 20.dp).align(Alignment.CenterHorizontally)
        ) {
            Text(if (isDemoMode) "⏹  Stop Demo" else "▶  Demo Mode")
        }

        // session history - save session bottom + list from Room
        Button(onClick = onSaveSessionClick, modifier = Modifier.padding(bottom = 20.dp).align(Alignment.CenterHorizontally)){
            Text("Save Session")
        }
        Text(text = "Session History", fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
        if (sessionHistory.isEmpty()) {
            Text("No sessions saved yet. Run Demo Mode then tap Save Session.", fontSize = 14.sp)
        } else {
            sessionHistory.forEach { s ->
                Text(
                    text = "${fmt.format(Date(s.timestamp))} | ${s.steps} steps | ${"%.1f".format(s.calories)} kcal|${"%.2f".format(s.distanceKm)} km",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
    }
}





// ASSIGNMENT 4 -- Implement this! See Hour 3 for the full spec.
//   Launch a coroutine on Dispatchers.IO and insert `session` into the
//   database via database.sessionDao(). Database calls are not allowed
//   on the main thread. Once the insert finishes, switch back to
//   Dispatchers.Main (withContext) to show a confirmation Toast and
//   call loadHistory() again so the new session appears in the list.4