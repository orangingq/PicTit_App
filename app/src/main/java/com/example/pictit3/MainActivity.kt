package com.example.pictit3

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.pictit3.databinding.ActivityMainBinding
import com.example.pictit3.ml.LiteModelSsdMobilenetV11Metadata2
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Timer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.max
import kotlin.math.min


class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PicTit"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val MAX_FONT_SIZE = 96F
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var imageView: ImageView
    private lateinit var currentPhotoPath: String
    private lateinit var file: File
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val finishtimeed: Long = 1000
    private var presstime: Long = 0
    private val recordProcess:Timer = Timer()
    private var audioTextList = arrayListOf<String>()
    private var taglist = arrayListOf<String>()
    private lateinit var button: MaterialButton
    private lateinit var toggleGroup: MaterialButtonToggleGroup

    var modelPath = "lite-model_yamnet_classification_tflite_1.tflite"

    // TODO 2.2: defining the minimum threshold
    var probabilityThreshold: Float = 0.3f


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        imageView = viewBinding.imageView
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
            startAudioRecording() // audio recording start!
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onBackPressed() {
        val tempTime = System.currentTimeMillis()
        val intervalTime: Long = tempTime - presstime
        if (0 <= intervalTime && finishtimeed >= intervalTime) {
            recordProcess.cancel()
            finish()
        } else {
            presstime = tempTime
            Toast.makeText(applicationContext, "Press again to finish the app.", Toast.LENGTH_SHORT).show()
//            recordProcess.cancel()
//            finish()
//            startActivity(Intent(this, MainActivity.javaClass))

        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // Image Capture
            imageCapture = ImageCapture.Builder()
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageCapture, preview)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startAudioRecording(){
        // TODO 2.3: Loading the model from the assets folder
        val classifier = AudioClassifier.createFromFile(this, modelPath)

        // TODO 3.1: Creating an audio recorder
        val tensor = classifier.createInputTensorAudio()

        // TODO 3.2: showing the audio recorder specification
        val format = classifier.requiredTensorAudioFormat
        val recorderSpecs = "Number Of Channels: ${format.channels}\n" +
                "Sample Rate: ${format.sampleRate}"
//        recorderSpecsTextView.text = recorderSpecs
        Log.d("startAudioRecording", recorderSpecs)

        // TODO 3.3: Creating
        val record = classifier.createAudioRecord()
        record.startRecording()

        recordProcess.scheduleAtFixedRate(1, 500) {

            // TODO 4.1: Classifing audio data
            val numberOfSamples = tensor.load(record)
            val output = classifier.classify(tensor)
            Log.d("startAudioRecording", "output: "+output.toString())

            // TODO 4.2: Filtering out classifications with low probability
            val filteredModelOutput = output[0].categories.filter {
                it.score > probabilityThreshold && !(it.label in audioTextList)
            }

            for(it in output[0].categories){
                if (audioTextList.size >= 5) {
                    recordProcess.cancel()
                    break
                }
                if(it.score <= probabilityThreshold) continue
                if(it.label in audioTextList) continue

                audioTextList.add(it.label)
            }
//            audioTextList
//            // TODO 4.3: Creating a multiline string with the filtered results
//            val outputStr = filteredModelOutput.map{it.label}

//            // TODO 4.4: Updating the UI
//            if (outputStr.isNotEmpty())
//                runOnUiThread {
//                    addTextButton(outputStr, true)
//                }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp: String = SimpleDateFormat(/* pattern = */ "yyyyMMdd_HHmmss").format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        file = File.createTempFile(
            "JPEG_${timeStamp}_", /* prefix */
            ".jpg", /* suffix */
            storageDir /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            currentPhotoPath = absolutePath
        }
        return file
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                createImageFile()
            )
            .build()

        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                @RequiresApi(Build.VERSION_CODES.Q)
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap: Bitmap = getCapturedImage()
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    val toast:Toast = Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT)
                    toast.setGravity(Gravity.TOP, 0, 20)
                    toast.show()
                    setViewAndDetect(bitmap)
                }
            }
        )
    }

    private fun runObjectDetection2(bitmap:Bitmap){
        val model = LiteModelSsdMobilenetV11Metadata2.newInstance(baseContext)

        // Creates inputs for reference.
        val image = TensorImage.fromBitmap(bitmap)

        // Runs model inference and gets result.
        val outputs = model.process(image)
        val detectionResult = outputs.detectionResultList.sortedBy{it.scoreAsFloat}.asReversed()
        Log.d("runObjectDetection2", "detectionResult: " + detectionResult.size.toString())
        Log.d("runObjectDetection2", "detectionResult: " + detectionResult[0].categoryAsString +" - "+ detectionResult[0].scoreAsFloat.toString())

        // Parse the detection result and show it
        var selected = arrayListOf<String>()
        var detectionResults = arrayListOf<DetectionResult>()
        for (it in detectionResult){
            val location = it.locationAsRectF;
            val category = it.categoryAsString;
            val score = it.scoreAsFloat;
            if (category in selected) continue
            if(score < 0.2 || selected.size > 4) break

            selected.add(category)

            Log.d("runObjectDetection2", category+ ": " + score.toString())
            val text = "${category}, ${score.times(100).toInt()}%"

            // Create a data object to display the detection result
            detectionResults.add(DetectionResult(location, text))
        }

        recordProcess.cancel()

        runOnUiThread {
            addTextButton(selected, false)
            addTextButton(audioTextList, true)
        }
        Log.d("runObjectDetection2", "selected: "+selected.toString())
        Log.d("runObjectDetection2", "audioTextList: "+audioTextList.toString())

        // Draw the detection result on the bitmap and show it.
        val imgWithResult = drawDetectionResult(bitmap, detectionResults.map{it})
        runOnUiThread {
            imageView.setImageBitmap(imgWithResult)
        }
        Log.d("runObjectDetection2", "drawing result clear")

        // Releases model resources if no longer used.
        model.close()
    }

    private fun setViewAndDetect(bitmap: Bitmap) {
        // Display capture image
        viewBinding.imageCaptureButton.visibility = View.INVISIBLE
        viewBinding.viewFinder.visibility = View.INVISIBLE
        imageView.visibility = View.VISIBLE
        viewBinding.audioTagText.visibility = View.VISIBLE
        viewBinding.imageTagText.visibility = View.VISIBLE

        /*  Run ODT and display result
         *  Note that we run this in the background thread to avoid blocking the app UI because
         *  TFLite object detection is a synchronised process. */
        lifecycleScope.launch(Dispatchers.Default) {
            runObjectDetection2(bitmap)
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getCapturedImage(): Bitmap {
        // Get the dimensions of the View
        val targetW: Int = imageView.width
        val targetH: Int = imageView.height
        val photoPath: String = file.absolutePath
        if(photoPath == "") Log.e("getCapturedImage", "photoPath: null")
        else Log.d("getCapturedImage", "photoPath: "+photoPath)

        val bmOptions = BitmapFactory.Options().apply {
            // Get the dimensions of the bitmap
            inJustDecodeBounds = true

            BitmapFactory.decodeFile(photoPath, this)

            val photoW: Int = outWidth
            val photoH: Int = outHeight

            // Determine how much to scale down the image
            val scaleFactor: Int = max(1, min(photoW / targetW, photoH / targetH))

            // Decode the image file into a Bitmap sized to fill the View
            inJustDecodeBounds = false
            inSampleSize = scaleFactor
            inMutable = true
        }
        Log.d("getCapturedImage", "file path: "+photoPath + ", file: "+file.name)
        val exifInterface = ExifInterface(photoPath)
        Log.d("getCapturedImage", "exifInterface: "+exifInterface.toString())

        val orientation = exifInterface.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )

        val bitmap = BitmapFactory.decodeFile(photoPath, bmOptions)
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                rotateImage(bitmap, 90f)
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                rotateImage(bitmap, 180f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                rotateImage(bitmap, 270f)
            }
            else -> {
                bitmap
            }
        }
    }


    private fun drawDetectionResult(bitmap: Bitmap, detectionResults: List<DetectionResult>): Bitmap {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        val pen = Paint()
        pen.textAlign = Paint.Align.LEFT

        detectionResults.forEach {
            // draw bounding box
            pen.color = Color.RED
            pen.strokeWidth = 8F
            pen.style = Paint.Style.STROKE
            val box = it.boundingBox
            canvas.drawRect(box, pen)

            val tagSize = Rect(0, 0, 0, 0)

            // calculate the right font size
            pen.style = Paint.Style.FILL_AND_STROKE
            pen.color = Color.YELLOW
            pen.strokeWidth = 2F

            pen.textSize = MAX_FONT_SIZE
            pen.getTextBounds(it.text, 0, it.text.length, tagSize)
            val fontSize: Float = pen.textSize * box.width() / tagSize.width()

            // adjust the font size so texts are inside the bounding box
            if (fontSize < pen.textSize) pen.textSize = fontSize

            var margin = (box.width() - tagSize.width()) / 2.0F
            if (margin < 0F) margin = 0F
            canvas.drawText(
                it.text, box.left + margin,
                box.top + tagSize.height().times(1F), pen
            )
        }
        return outputBitmap
    }

    private fun rotateImage(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            matrix, true
        )
    }


    private fun addTextButton(textlist: List<String>, isAudio: Boolean){
        var cnt = 1
        toggleGroup = viewBinding.audiotoggle1 // just for compiler

        for(text in textlist){
            // set the button parameters and add into the layout
            if (cnt > 5) break
            if(isAudio){
                if(cnt == 1) toggleGroup = viewBinding.audiotoggle1
                else if(cnt == 2) toggleGroup = viewBinding.audiotoggle2
                else if (cnt== 3) toggleGroup = viewBinding.audiotoggle3
                else if (cnt== 4) toggleGroup = viewBinding.audiotoggle4
                else toggleGroup = viewBinding.audiotoggle5
            }else{
                if(cnt == 1) toggleGroup = viewBinding.imagetoggle1
                else if(cnt == 2) toggleGroup = viewBinding.imagetoggle2
                else if (cnt== 3) toggleGroup = viewBinding.imagetoggle3
                else if (cnt== 4) toggleGroup = viewBinding.imagetoggle4
                else toggleGroup = viewBinding.imagetoggle5
            }

            button = toggleGroup.getChildAt(0) as MaterialButton
            button.visibility = View.VISIBLE
            button.text = text
            toggleGroup.check(button.id)

            button.addOnCheckedChangeListener({ buttonView, isChecked ->
                    if(isChecked){
                        taglist.add(buttonView.text.toString())
                        Log.d("addTextButton", "added: "+buttonView.text.toString()+ ", taglist: "+taglist.toString())
                    }else{
                        taglist.remove(buttonView.text.toString())
                    }
                })
//            button.setOnClickListener {
//                taglist.add(text)
//                Log.d("addTextButton", "added: "+text+ ", taglist: "+taglist.toString())
//            }

            cnt+= 1
        }
    }


//    override fun onDestroy() {
//        super.onDestroy()
//        cameraExecutor.shutdown()
//    }



//    private fun getRealPathFromURI(contentUri: Uri): String {
//        val proj = arrayOf(MediaStore.Images.Media.DATA)
//        val loader = CursorLoader(baseContext, contentUri, proj, null, null, null)
//        val cursor: Cursor = loader.loadInBackground() ?: return ""
//
//        val column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
//        cursor.moveToFirst()
//        val result = cursor.getString(column_index)
//        cursor.close()
//        Log.d("getRealPathFromURI", "result: "+result)
//        return result
//    }
}

/**
 * DetectionResult
 *      A class to store the visualization info of a detected object.
 */
data class DetectionResult(val boundingBox: RectF, val text: String)