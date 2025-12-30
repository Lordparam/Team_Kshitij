package com.example.dead_inside

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {
    private var anchorMapX = 0f
    private var anchorMapY = 0f
    private val mapPixelWidth = 1536f
    private val mapPixelHeight = 1024f

    private lateinit var mapMatrix: Matrix
    private lateinit var pointerView: ImageView
    private lateinit var tvDebug: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var startLat = Double.NaN
    private var startLng = Double.NaN
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val splashContainer = findViewById<ConstraintLayout>(R.id.splashContainer)
        val dashboardContainer = findViewById<ConstraintLayout>(R.id.dashboardContainer)
        val titleText = findViewById<TextView>(R.id.tvCampusNavigation)
        val startBtn = findViewById<View>(R.id.btnStartNavigation)
        val mapContainer = findViewById<View>(R.id.mapContainer)
        val mapView = findViewById<ImageView>(R.id.campusMapView)
        pointerView = findViewById(R.id.locationPointer)
        tvDebug = findViewById(R.id.tvDebugInfo)

        mapMatrix = Matrix()
        mapView.imageMatrix = mapMatrix

        // 1. PINCH TO ZOOM LOGIC
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                mapMatrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                mapView.imageMatrix = mapMatrix
                updatePointerPosition() // Re-sync pointer after zoom
                return true
            }
        })

        // 2. DRAG / PAN LOGIC
        var lastTouchX = 0f
        var lastTouchY = 0f
        var isDragging = false

        mapView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event) // Handle zoom first

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isDragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && isDragging) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        mapMatrix.postTranslate(dx, dy)
                        mapView.imageMatrix = mapMatrix
                        updatePointerPosition() // Re-sync pointer after drag
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
            true
        }

        // Splash Transition
        Handler(Looper.getMainLooper()).postDelayed({
            splashContainer.visibility = View.GONE
            dashboardContainer.visibility = View.VISIBLE
        }, 2000)

        startBtn.setOnClickListener {
            checkPermissionsAndStart()
            dashboardContainer.visibility = View.GONE
            mapContainer.visibility = View.VISIBLE
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        } else {
            startGpsUpdates()
        }
    }

    private fun startGpsUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                if (startLat.isNaN()) {
                    startLat = location.latitude
                    startLng = location.longitude
                    Toast.makeText(this@MainActivity, "Simulation Started!", Toast.LENGTH_SHORT).show()
                }

                // Update only if moved ~0.5m
                if (lastLat.isNaN() || Math.abs(location.latitude - lastLat) > 0.000005) {
                    lastLat = location.latitude
                    lastLng = location.longitude
                    calculateSimulatedPosition(location.latitude, location.longitude)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun calculateSimulatedPosition(currentLat: Double, currentLng: Double) {
        val latOffset = currentLat - startLat
        val lngOffset = currentLng - startLng

        // Multiplier increased from 60000f to 90000f for 50% faster movement
        // This makes every physical meter you walk translate to more pixels on your map.
        anchorMapX = (mapPixelWidth / 2f) + (lngOffset.toFloat() * 90000f)
        anchorMapY = (mapPixelHeight / 2f) - (latOffset.toFloat() * 90000f)

        // Ensure the pointer stays within the map boundaries while you test
        anchorMapX = anchorMapX.coerceIn(0f, mapPixelWidth)
        anchorMapY = anchorMapY.coerceIn(0f, mapPixelHeight)

        tvDebug.text = "Map X: ${anchorMapX.toInt()}, Y: ${anchorMapY.toInt()}"
        updatePointerPosition()
    }
    private fun updatePointerPosition() {
        val pts = floatArrayOf(anchorMapX, anchorMapY)
        mapMatrix.mapPoints(pts) // CRITICAL: This maps image pixels to screen pixels

        val offset = if (pointerView.width > 0) pointerView.width / 2f else 40f
        pointerView.translationX = pts[0] - offset
        pointerView.translationY = pts[1] - offset
        pointerView.bringToFront() //

        if (pointerView.visibility != View.VISIBLE) pointerView.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        if (::locationCallback.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}