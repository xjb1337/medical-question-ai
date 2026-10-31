package com.xjb.medicalquestionai.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.CaptureTarget
import com.xjb.medicalquestionai.data.ImageStore
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.components.ErrorBanner
import com.xjb.medicalquestionai.ui.returnFromDetail

@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    navController: NavController,
    source: String,
    sessionId: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isImporting by viewModel.isImporting.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val notice by viewModel.draftNotice.collectAsState()
    val effectiveSessionId = sessionId.ifBlank { draft.sessionId }
    val snackbarHostState = remember { SnackbarHostState() }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var captureToken by rememberSaveable { mutableStateOf<String?>(null) }
    val activity = context.findActivityOrNull()

    fun exitCamera() {
        viewModel.invalidateCameraCapture(captureToken)
        captureToken = null
        if (source == "next_question") {
            navController.navigate("home") {
                popUpTo("home") { inclusive = false }
                launchSingleTop = true
            }
        } else {
            navController.returnFromDetail()
        }
    }

    BackHandler { exitCamera() }

    val latestCaptureToken = rememberUpdatedState(captureToken)
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) {
                viewModel.invalidateCameraCapture(latestCaptureToken.value)
                captureToken = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) {
                viewModel.invalidateCameraCapture(latestCaptureToken.value)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner, hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose { }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        var boundUseCases = emptyArray<UseCase>()
        val listener = Runnable {
            // The future may complete after the user has already left this page.
            if (disposed) return@Runnable
            try {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                boundProvider = provider
                boundUseCases = arrayOf(preview, capture)
                imageCapture = capture
                cameraError = null
            } catch (e: Exception) {
                cameraError = "相机启动失败：${e.message ?: "请检查设备相机"}"
            }
        }
        cameraProviderFuture.addListener(listener, executor)
        onDispose {
            disposed = true
            // Never wait for camera initialization on the UI thread during back navigation.
            boundProvider?.unbind(*boundUseCases)
            camera = null
            imageCapture = null
        }
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeDraftNotice()
        }
    }

    fun capture() {
        if (isCapturing || isImporting) return
        if (draft.images.size >= ImageStore.MAX_IMAGES) {
            cameraError = "最多添加 ${ImageStore.MAX_IMAGES} 张图片"
            return
        }
        val capture = imageCapture
        if (capture == null) {
            cameraError = "相机尚未准备好，请稍等片刻"
            return
        }
        val target: CaptureTarget = runCatching { viewModel.createCaptureTarget() }
            .getOrElse {
                cameraError = "无法创建照片文件：${it.message}"
                return
            }
        isCapturing = true
        val token = viewModel.beginCameraCapture()
        captureToken = token
        val outputOptions = ImageCapture.OutputFileOptions.Builder(target.file).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    viewModel.addCameraImage(target.uri, target.file, effectiveSessionId, token) {
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                    isCapturing = false
                }

                override fun onError(exception: ImageCaptureException) {
                    viewModel.invalidateCameraCapture(token)
                    if (captureToken == token) captureToken = null
                    viewModel.deleteCapture(target.file)
                    cameraError = "拍照失败：${exception.message ?: "请重试"}"
                    isCapturing = false
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (source == "next_question") "拍摄下一题" else "拍摄题目",
                subtitle = "对准题干与选项，保持清晰完整",
                onBack = { exitCamera() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (cameraError != null) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(cameraError!!, onDismiss = { cameraError = null })
            }
            if (permissionDenied) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner("相机权限被关闭。可点击下方按钮重新授权，或返回首页从相册选择图片。")
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
                if (hasPermission) {
                    // Composition guide only: the captured image remains uncropped.
                    Canvas(Modifier.fillMaxSize().padding(28.dp)) {
                        val left = 0f
                        val right = size.width
                        val top = size.height * 0.2f
                        val bottom = size.height * 0.8f
                        val arm = 24.dp.toPx()
                        val stroke = 3.dp.toPx()
                        listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom))
                            .forEach { corner ->
                                val dx = if (corner.x == left) arm else -arm
                                val dy = if (corner.y == top) arm else -arm
                                drawLine(Color.White.copy(alpha = 0.85f), corner,
                                    Offset(corner.x + dx, corner.y), stroke)
                                drawLine(Color.White.copy(alpha = 0.85f), corner,
                                    Offset(corner.x, corner.y + dy), stroke)
                            }
                    }
                }
                if (!hasPermission) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(54.dp),
                            tint = Color.White
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "需要相机权限才能拍摄题目",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("允许使用相机")
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (camera?.cameraInfo?.hasFlashUnit() == true) {
                        IconButton(
                            onClick = {
                                torchOn = !torchOn
                                camera?.cameraControl?.enableTorch(torchOn)
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.38f))
                        ) {
                            Icon(
                                if (torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                                contentDescription = if (torchOn) "关闭补光灯" else "打开补光灯",
                                tint = Color.White
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.42f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                ) {
                    Text(
                        "拍全题干和所有选项 · 保持纸面平整",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = ::capture,
                    enabled = hasPermission && !isCapturing && !isImporting,
                    shape = CircleShape,
                    modifier = Modifier.size(78.dp)
                ) {
                    if (isCapturing || isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = "拍照",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(
                    Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "拍摄后可在首页旋转、裁剪、调整顺序。涉及患者信息时，请先遮挡姓名、住院号等。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
