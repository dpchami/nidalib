package com.kivo.sajilib

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.*
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BackDetectActivity : AppCompatActivity(), ImageAnalysis.Analyzer, SurfaceHolder.Callback {
    companion object {
        private const val TAG = "BackDetectActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private val progressTextView by lazy { findViewById<TextView>(R.id.progress_text) }
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var surfaceView: SurfaceView
    private var canvas: Canvas = Canvas()
    private var detectedPath: String? = null
    private var detectedBitmap: Bitmap? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var detectorAddr: Long = 0L
    private lateinit var nv21: ByteArray
    private val labelsMap = arrayListOf<String>()
    private var rotation: Int = 0
    private val cardDetectActivity = CardDetectActivity()

    //detection camera specification
    var xOffset:Int = 0
    var yOffset: Int = 0
    var boxWidth: Int = 0
    var boxHeight: Int = 0

    //Capture detection variables
    var backDetectCount = 0
    var backDetect:Int = 0
    var descDetect:Int = 0


    //for custom preview
    private lateinit var holder: SurfaceHolder

    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {

                rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener.disable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_back_detect)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, BackDetectActivity.REQUIRED_PERMISSIONS, BackDetectActivity.REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Set the detections drawings surface transparent
        surfaceView = findViewById(R.id.overlay)
        surfaceView.setZOrderOnTop(true)

        holder = surfaceView.holder
        holder.setFormat(PixelFormat.TRANSPARENT)
        holder.addCallback(this)

        progressTextView.setText("SEARCHING ...")

        loadLabels()
    }

    private fun allPermissionsGranted() = BackDetectActivity.REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == BackDetectActivity.REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {

        previewView = findViewById(R.id.previewView)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                    .setTargetResolution(Size(768, 1024))
                    //.setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(rotation)
                    .build()
                    .also {
                        //it.setSurfaceProvider(viewFinder.surfaceProvider)
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

            imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(768, 1024))
                    //.setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(rotation)
                    .build()

            // ImageAnalysis
            imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(768, 1024))
                    //.setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    // The analyzer can then be assigned to the instance
                    .also {
                        it.setAnalyzer(cameraExecutor, this)
                    }

            val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()


            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                val camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        //CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                        imageAnalyzer
                )
                // camera.cameraControl.enableTorch(true)

            } catch (exc: Exception) {
                Log.e(BackDetectActivity.TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }
    private fun drawDetection(
            detectionsArr: FloatArray,
            detectionIdx: Int
    ) {
        val pos = detectionIdx * 6 + 1
        val score = detectionsArr[pos + 0]
        val classId = detectionsArr[pos + 1]
        var xmin = detectionsArr[pos + 2]
        var ymin = detectionsArr[pos + 3]
        var xmax = detectionsArr[pos + 4]
        var ymax = detectionsArr[pos + 5]

        // Filter by score
        if (score < 0.20) return

        val label = labelsMap[classId.toInt()]

        detectCounter(label, score)

        runOnUiThread(Runnable {
            notificationView()
        })

        if (backDetectCount > 10) {
            Log.d(BackDetectActivity.TAG, "nida card back detector: " + backDetectCount)
            if (detectedPath.isNullOrEmpty()){
                takePhoto();

                if (detectedBitmap != null){
                    backDetectCount = 0
                    saveBitMap(this, detectedBitmap!!)
                }

            } else {
                //reset count
                sendResultBack(detectedPath!!)
            }
        }
    }

    fun sendResultBack(path: String) {
        Log.d(BackDetectActivity.TAG, "has to return path $path")
        val intent = Intent()
        intent.putExtra("MESSAGE", path)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    fun DrawFocusRect() {
        //display canvas initiate
        canvas = holder.lockCanvas()
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)

        //inner frame constant
        val radius = 10.0f // should be retrieved from resources and defined as dp
        val borderWidth = 2.0f // ditto
        val innerRectFillColor = 0x33000000 // or whatever shade it should be

        val innerRectFill = Paint()
        innerRectFill.setStyle(Paint.Style.STROKE)
        innerRectFill.setColor(innerRectFillColor)
        innerRectFill.setStrokeWidth(5f)

        val  rectF = boxGenerator(previewView.width, previewView.height, 0.95)

        //draw inner Frame rectangle
        canvas.drawRoundRect(rectF, radius, radius, innerRectFill)
        // then draw the border
        innerRectFill.color = Color.WHITE
        innerRectFill.strokeWidth = borderWidth
        innerRectFill.style = Paint.Style.STROKE
        canvas.drawRoundRect(rectF, radius, radius, innerRectFill)


        //draw outside
        // same constants as above except innerRectFillColor is not used. Instead:
        val outerFillColor = 0x77000000
        // set up some outsidw frame constants
        val w = canvas.width
        val h = canvas.height

        // first create an off-screen bitmap and its canvas
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val auxCanvas = Canvas(bitmap)

        // then fill the bitmap with the desired outside color
        val outerFramePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        outerFramePaint.color = outerFillColor
        outerFramePaint.style = Paint.Style.FILL
        auxCanvas.drawPaint(outerFramePaint)

        // then punch a transparent hole in the shape of the rect
        outerFramePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        auxCanvas.drawRoundRect(rectF, radius, radius, outerFramePaint)

        // then draw the white rect border (being sure to get rid of the xfer mode!)
        outerFramePaint.xfermode = null
        outerFramePaint.color = Color.WHITE
        outerFramePaint.style = Paint.Style.STROKE
        auxCanvas.drawRoundRect(rectF, radius, radius, outerFramePaint)
        //canvas.drawRoundRect(rect, radius, radius, _paint)

        // finally, draw the whole thing to the original canvas
        canvas.drawBitmap(bitmap, 0f, 0f, outerFramePaint)

        holder.unlockCanvasAndPost(canvas)
    }

    private fun saveBitMap(context: Context, finalBitmap: Bitmap) = try {
        val dirName = "LibFolder"
        val pictureFileDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), dirName)
        if (!pictureFileDir.exists()) {
            val isDirectoryCreated = pictureFileDir.mkdirs()
            if (!isDirectoryCreated) {
                Log.i("TAG", "Can't create directory to save the image")
                throw Exception("Can't create directory")
            }

        }
        val filename = pictureFileDir.path + File.separator + System.currentTimeMillis() + ".jpg"
        val pictureFile = File(filename)
        pictureFile.createNewFile()

        val captureRect = boxGenerator(finalBitmap.width,finalBitmap.height,1.0)

        val croppedBitmap = Bitmap.createBitmap(finalBitmap, captureRect.left.toInt() , captureRect.top.toInt(), (captureRect.right-captureRect.left).toInt(), (captureRect.bottom-captureRect.top).toInt(), null, true)

        val oStream = FileOutputStream(pictureFile)
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, oStream)
        oStream.flush()
        oStream.close()
        Log.d(TAG, "Save Image Successfully..")
        detectedPath = pictureFile.path
    } catch (e: IOException) {
        e.printStackTrace()
    } catch (e: Exception) {
        e.printStackTrace()
    }

    private fun boxGenerator(frameWidth: Int, frameHeight: Int, factor: Double = 0.8 ) : RectF {

        val width = if (rotation == 0 || rotation == 180) frameWidth else frameHeight
        val height = if (rotation == 0 || rotation == 180) frameHeight else frameWidth

        val left: Int
        val right: Int
        val top: Int
        val bottom: Int
        var diameter: Int

        diameter = width

        if (height < width) {
            diameter = height
        }

        val offset: Double = (factor * diameter)
        diameter = offset.toInt()


        left = width/2 - diameter/2
        top  = height/2 - diameter/3
        right = width/2 + diameter/2
        bottom = height/2 + diameter/3

        //set box global parameters
        xOffset = left
        yOffset = top
        boxHeight = bottom - top
        boxWidth = right - left

        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Set up image capture listener, which is triggered after photo has
        imageCapture.takePicture(cameraExecutor, object :
                ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                //get bitmap from image
                detectedBitmap = imageProxyToBitmap(image)
                super.onCaptureSuccess(image)
                image.close()
            }

            override fun onError(exception: ImageCaptureException) {
                super.onError(exception)
            }
        })
    }

    private fun loadLabels() {
        val labelsInput = this.assets.open("label_map.txt")
        val br = BufferedReader(InputStreamReader(labelsInput))
        var line = br.readLine()
        while (line != null) {
            labelsMap.add(line)
            line = br.readLine()
        }
        br.close()
    }

    private fun notificationView() {
        var msg = "PLACE CARD ON FRAME ...."
        if (backDetectCount in 1..3) {
            msg = "PLEASE WAIT"
        } else if (backDetectCount in 4..8) {
            msg = "PROCESSING ..."
        } else if (backDetectCount >= 9) {
            msg = "CAPTURING ..."
        }
        progressTextView.setText(msg)
    }

    private fun detectCounter(label: String, score: Float){
        if ((label=="back") && (score > 0.30)) {
            backDetect++
        } else if ((label=="desc") && (score > 0.30)) {
            descDetect++
        } else {
            backDetectCount = 0
        }

        if (backDetect > 4 && descDetect > 3) {
            backDetectCount++
        }
    }

    private fun toastAlert(message: String, duration: Int = Toast.LENGTH_SHORT) {
        runOnUiThread(Runnable {
            Toast.makeText(applicationContext, message, duration).show()
        })
    }

    override fun analyze(image: ImageProxy) {
        if (image.planes.size < 3) {return}
        if (detectorAddr == 0L) {
            detectorAddr = cardDetectActivity.initDetector(this.assets)
        }

        val rotation = image.imageInfo.rotationDegrees
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        if (!::nv21.isInitialized) {
            nv21 = ByteArray(ySize + uSize + vSize)
        }

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        val res = cardDetectActivity.detect(detectorAddr, nv21, image.width, image.height, rotation)

        if (canvas != null) {
            // Draw the detections, in our case there are only 3
            for (i in 0 until res[0].toInt()){
                this.drawDetection(res, i)
            }
        }
        image.close();
    }

    override fun surfaceCreated(p0: SurfaceHolder) {}

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        DrawFocusRect()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) { }
}