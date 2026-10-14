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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.ButtonDefaults
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat




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
    private var stepCounterSensor: Sensor? = null

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

    // Step tracking
    private var sessionStepCount = 0
    private var initialStepCount = -1 // anchor point (hardware counter is total since boot)

    // Health metric display state
    private var stepCountText by mutableStateOf("Steps: 0")
    private var caloriesText by mutableStateOf("Calories: 0.0 kcal")
    private var distanceText by mutableStateOf("Distance: 0.0 km")
    private val ACTIVITY_PERMISSION_CODE = 100

    // Demo mode - Compose state so the button recomposes when it flips
    private var isDemoMode by mutableStateOf(false)
    private val demoHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val STATIONARY_THRESHOLD = 2.8f
        private const val WALKING_THRESHOLD = 12.8f
        private const val KCAL_PER_STEP = 0.04f
        private const val KM_PER_STEP = 0.000762f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSensors()
        requestActivityPermission()

        setContent {
            GoldieFitnessTracker2Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FitnessTrackerScreen(
                        motionText = motionText,
                        gyroText = gyroText,
                        directionText = directionText,
                        stepCountText = stepCountText,
                        caloriesText = caloriesText,
                        distanceText = distanceText,
                        isDemoMode = isDemoMode,
                        onRefreshChartClick = { setupChart() },
                        onChartCreated = { chart -> barChart = chart; setupChart() },
                        onDemoModeClick = { toggleDemoMode() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerSensors()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        demoHandler.removeCallbacks(demoRunnable)
        isDemoMode = false
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    private fun registerSensors() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        stepCounterSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    private fun requestActivityPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), ACTIVITY_PERMISSION_CODE)


    }

    // Re-register once the user answers the permission dialog so the step counter starts
    //override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
    // super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    // if (requestCode == ACTIVITY_PERMISSION_CODE) {
    // sensorManager.unregisterListener(this)
    // registerSensors()



    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelValues = event.values.clone()
            }
            Sensor.TYPE_STEP_COUNTER-> {
                handleStepCounter(event.values[0].toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { }

    private fun handleAccelerometer(values: FloatArray) {
        val x = values[0] - baselineX
        val y = values[1] - baselineY
        val z = values[2] - baselineZ // was baselineY - bug
        val magnitude = sqrt(x * x + y * y + z * z)
        motionText = "Motion: ${classifyMotion(magnitude)}"
    }

    // values[] are rotation rates in radians/second
    private fun handleGyroscope(values: FloatArray) {
        gyroText = "Rotation (Pitch, Roll, Yaw): ${"%.2f".format(values[0])}, ${"%.2f".format(values[1])}, ${"%.2f".format(values[2])}"
    }

    // Sensor fusion: accel gives gravity (tilt), magnet gives North.
    // getRotationMatrix() returns false until both sensors have fired.
    private fun updateCompass() {
        val rm = FloatArray(9)
        val im = FloatArray(9)
        if (SensorManager.getRotationMatrix(rm, im, accelValues, magnetValues)) {
            val orientation = FloatArray(3);SensorManager.getOrientation(rm, orientation)
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

    // Hardware counter = total steps since boot, so subtract the first reading
    private fun handleStepCounter(totalSteps: Int) {
        if (initialStepCount == -1) initialStepCount = totalSteps
        sessionStepCount = totalSteps - initialStepCount
        updateHealthMetrics()
        updateChartWithSteps()
    }

    private fun updateHealthMetrics() {
        stepCountText = "Steps: $sessionStepCount"
        caloriesText = "Calories: ${"%.1f".format(sessionStepCount * KCAL_PER_STEP)} kcal"
        distanceText = "Distance: ${"%.2f".format(sessionStepCount * KM_PER_STEP)} km"
    }
    private fun calculateCalories(steps: Int): Float = 0f

    private fun calculateDistance(steps: Int): Float = 0f


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
    private fun setupChart() {
        if (!::barChart.isInitialized) return
        val entries = hourlyStep.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }
        val dataSet = BarDataSet(entries, "Steps (3-hour blocks)").apply {
            color = Color.CYAN // already an Int - no .toInt() needed
            valueTextSize = 14f
        }
        barChart.apply {
            data = BarData(dataSet)
            description.text = "Step Count"
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            axisRight.isEnabled = false
            setFitBars(true)
            invalidate()
        }
    }


    private fun classifyMotion(magnitude: Float): String = when {
        magnitude < STATIONARY_THRESHOLD -> "Stationary"
        magnitude < WALKING_THRESHOLD -> "Walking"
        else -> "Jogging"
    }


    // Renamed from GoldieFitnessTracker2Theme (that name belongs to your theme) and
// given every parameter it actually uses.
    @Composable
    fun FitnessTrackerScreen(
        motionText: String,
        gyroText: String,
        directionText: String,
        stepCountText: String,
        caloriesText: String,
        distanceText: String,
        isDemoMode: Boolean,
        onRefreshChartClick: () -> Unit,
        onChartCreated: (BarChart) -> Unit,
        onDemoModeClick: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(26.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(text = motionText, fontSize = 24.sp, modifier = Modifier.padding(bottom = 26.dp))
            Text(text = gyroText, fontSize = 24.sp, modifier = Modifier.padding(bottom = 26.dp))
            Text(text = directionText, fontSize = 24.sp, modifier = Modifier.padding(bottom = 26.dp))

            Text(text = stepCountText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            Text(text = caloriesText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            Text(text = distanceText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))

            AndroidView(
                factory = { ctx -> BarChart(ctx).also(onChartCreated) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(bottom = 26.dp)
            )

            Button(
                onClick = onRefreshChartClick,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Refresh Charts")

            }

            Button(
                onClick = onDemoModeClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDemoMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (isDemoMode) "⏹  Stop Demo" else "▶  Demo Mode")
            }
        }
    }
}