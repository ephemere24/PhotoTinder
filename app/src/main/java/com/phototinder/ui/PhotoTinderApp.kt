package com.phototinder.ui

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.PendingIntent
import android.content.IntentSender
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

// ─── Data ─────────────────────────────────────────────────────────

data class PhotoItem(
    val uri: Uri,
    val name: String,
    val albumName: String
)

enum class SwipeAction { KEEP, TRASH }

const val DELETE_REQUEST_CODE = 1001

// ─── Main App ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoTinderApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var trashedPhotos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var processedCount by remember { mutableIntStateOf(0) }
    var isProcessing by remember { mutableStateOf(false) }
    var showPermissionDenied by remember { mutableStateOf(false) }
    var albums by remember { mutableStateOf<List<Pair<String, List<PhotoItem>>>>(emptyList()) }
    var selectedAlbums by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showStatsToast by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember { mutableStateOf(false) }

    // Check initial permission
    LaunchedEffect(Unit) {
        hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPermission) {
            val result = withContext(Dispatchers.IO) { fetchAllAlbums(context) }
            albums = result.first
            selectedAlbums = albums.map { it.first }.toSet()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPermission = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { fetchAllAlbums(context) }
                albums = result.first
                selectedAlbums = albums.map { it.first }.toSet()
            }
        } else {
            showPermissionDenied = true
        }
    }

    val multiplePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            hasPermission = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { fetchAllAlbums(context) }
                albums = result.first
                selectedAlbums = albums.map { it.first }.toSet()
            }
        } else {
            showPermissionDenied = true
        }
    }

    LaunchedEffect(showStatsToast) {
        showStatsToast?.let {
            kotlinx.coroutines.delay(2000)
            showStatsToast = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "setup"
        ) {
            composable("setup") {
                SetupScreen(
                    albums = albums,
                    selectedAlbums = selectedAlbums,
                    onAlbumToggle = { album ->
                        selectedAlbums = if (album in selectedAlbums) {
                            selectedAlbums - album
                        } else {
                            selectedAlbums + album
                        }
                    },
                    onSelectAll = {
                        selectedAlbums = albums.map { it.first }.toSet()
                    },
                    onDeselectAll = {
                        selectedAlbums = emptySet()
                    },
                    onStart = {
                        if (!hasPermission) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                multiplePermissionsLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
                            } else {
                                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        } else {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    fetchPhotos(context, selectedAlbums.toList())
                                }
                                photos = result.second
                                currentIndex = 0
                                processedCount = 0
                                navController.navigate("swipe")
                            }
                        }
                    },
                    onTrash = { navController.navigate("trash") },
                    hasPermission = hasPermission,
                    isProcessing = isProcessing
                )
            }

            composable("swipe") {
                SwipeScreen(
                    photos = photos,
                    trashedPhotos = trashedPhotos,
                    currentIndex = currentIndex,
                    onSwiped = { action, photo ->
                        scope.launch {
                            when (action) {
                                SwipeAction.TRASH -> {
                                    withContext(Dispatchers.IO) {
                                        moveToTrash(context, photo.uri)
                                    }
                                    trashedPhotos = trashedPhotos + photo
                                }
                                SwipeAction.KEEP -> { /* nada, se queda */ }
                            }
                            photos = photos.filterIndexed { i, _ -> i != currentIndex }
                            processedCount++
                            if (photos.isEmpty() || currentIndex >= photos.size) {
                                showStatsToast = "¡Completaste! 🎉"
                            }
                        }
                    },
                    onIndexChange = { currentIndex = it },
                    onBack = { navController.popBackStack() },
                    onTrash = { navController.navigate("trash") },
                    onFinish = { navController.popBackStack() }
                )
            }

            composable("trash") {
                TrashScreen(
                    trashed = trashedPhotos,
                    onRecover = { photo ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                recoverFromTrash(context, photo.uri)
                            }
                            trashedPhotos = trashedPhotos - photo
                        }
                    },
                    onDeletePermanently = { photo ->
                        scope.launch {
                            val pendingIntent = withContext(Dispatchers.IO) {
                                deletePermanently(context, photo.uri)
                            }
                            if (pendingIntent != null) {
                                // Android R+: lanzar confirmación del sistema
                                try {
                                    val activity = context as? android.app.Activity
                                    activity?.startIntentSenderForResult(
                                        pendingIntent.intentSender,
                                        DELETE_REQUEST_CODE, null, 0, 0, 0
                                    )
                                } catch (_: Exception) {}
                            }
                            // En pre-R ya se borró directamente
                            trashedPhotos = trashedPhotos - photo
                        }
                    },
                    onEmptyTrash = {
                        scope.launch {
                            val pendingIntents = withContext(Dispatchers.IO) {
                                val uris = trashedPhotos.map { it.uri }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty()) {
                                    val pi = MediaStore.createDeleteRequest(
                                        context.contentResolver, uris
                                    )
                                    listOf(pi)
                                } else {
                                    uris.forEach { deletePermanently(context, it) }
                                    emptyList()
                                }
                            }
                            pendingIntents.forEach { pendingIntent ->
                                try {
                                    val activity = context as? android.app.Activity
                                    activity?.startIntentSenderForResult(
                                        pendingIntent.intentSender,
                                        DELETE_REQUEST_CODE, null, 0, 0, 0
                                    )
                                } catch (_: Exception) {}
                            }
                            trashedPhotos = emptyList()
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // Permission denied dialog
        if (showPermissionDenied) {
            AlertDialog(
                onDismissRequest = { showPermissionDenied = false },
                title = { Text("Permiso denegado", color = Color.White) },
                text = { Text("Necesitamos acceso a tus fotos. Ve a Ajustes > Permisos.", color = Color(0xFFCCCCCC)) },
                confirmButton = {
                    TextButton(onClick = { showPermissionDenied = false }) {
                        Text("OK", color = Color.White)
                    }
                },
                containerColor = Color(0xFF1A1A1A)
            )
        }

        // Stats toast
        showStatsToast?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        color = Color.White
                    )
                }
            }
        }
    }
}

// ─── Setup Screen ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    albums: List<Pair<String, List<PhotoItem>>>,
    selectedAlbums: Set<String>,
    onAlbumToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onStart: () -> Unit,
    onTrash: () -> Unit,
    hasPermission: Boolean,
    isProcessing: Boolean
) {
    val selectedCount = selectedAlbums.size
    val selectedPhotos = albums.filter { it.first in selectedAlbums }.sumOf { it.second.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header minimalista
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Photo Tinder",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                )
                IconButton(onClick = onTrash) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Papelera",
                        tint = Color(0xFF666666)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasPermission) "Selecciona los álbumes que quieras revisar"
                       else "Necesitamos acceso a tus fotos para empezar",
                fontSize = 13.sp,
                color = Color(0xFF888888),
                lineHeight = 18.sp
            )
        }

        // Divider sutil
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color(0xFF1A1A1A))
        )

        if (!hasPermission) {
            // Permission request UI
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 40.dp)
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFF444444)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Acceso a fotos",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Photo Tinder necesita permiso para acceder a tu galería de fotos",
                        textAlign = TextAlign.Center,
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Permitir acceso", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }
        } else if (albums.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Cargando álbumes...", color = Color(0xFF888888), fontSize = 13.sp)
                }
            }
        } else {
            // Stats en formato limpio
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = "$selectedCount",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "álbumes",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
                Column {
                    Text(
                        text = "$selectedPhotos",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "fotos",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
            }

            // Select/Deselect all — minimalista
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onSelectAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Seleccionar todo", color = Color.White, fontSize = 13.sp)
                }
                TextButton(
                    onClick = onDeselectAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Deseleccionar", color = Color(0xFF666666), fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Album list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                albums.sortedByDescending { it.second.size }.forEach { (albumName, albumPhotos) ->
                    val isSelected = albumName in selectedAlbums
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF1A1A1A) else Color(0xFF0D0D0D)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { onAlbumToggle(albumName) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color.White,
                                    uncheckedColor = Color(0xFF444444),
                                    checkmarkColor = Color.Black
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = albumName,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${albumPhotos.size}",
                                fontSize = 13.sp,
                                color = Color(0xFF666666)
                            )
                        }
                    }
                }
            }

            // Start button
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .height(52.dp),
                enabled = selectedAlbums.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF1A1A1A),
                    disabledContentColor = Color(0xFF444444)
                )
            ) {
                Text(
                    "Empezar",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (selectedPhotos > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "($selectedPhotos)",
                        fontSize = 13.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
        }
    }
}

// ─── Swipe Screen ─────────────────────────────────────────────────

@Composable
fun SwipeScreen(
    photos: List<PhotoItem>,
    trashedPhotos: List<PhotoItem>,
    currentIndex: Int,
    onSwiped: (SwipeAction, PhotoItem) -> Unit,
    onIndexChange: (Int) -> Unit,
    onBack: () -> Unit,
    onTrash: () -> Unit,
    onFinish: () -> Unit
) {
    if (photos.isEmpty() || currentIndex >= photos.size) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🎉", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("¡Listo!", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text("No quedan más fotos por revisar", color = Color(0xFFAAAAAA))
                if (trashedPhotos.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${trashedPhotos.size} fotos en la papelera", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = onBack) {
                        Text("Volver")
                    }
                    if (trashedPhotos.isNotEmpty()) {
                        Button(onClick = onTrash) {
                            Text("🗑️ Papelera")
                        }
                    }
                }
            }
        }
        return
    }

    val photo = photos[currentIndex]
    val total = photos.size
    val offsetX = remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 150f

    fun confirmSwipe(action: SwipeAction) {
        onSwiped(action, photo)
        offsetX.floatValue = 0f
        if (currentIndex + 1 < photos.size) {
            onIndexChange(currentIndex + 1)
        }
    }

    LaunchedEffect(currentIndex) {
        offsetX.floatValue = 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "${currentIndex + 1} / $total",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            IconButton(onClick = onTrash) {
                Icon(Icons.Default.Delete, contentDescription = "Trash", tint = Color.White)
            }
        }

        // Photo card with swipe gesture
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background indicators (behind the card)
            val progress = (offsetX.floatValue / swipeThreshold).coerceIn(-1f, 1f)
            val absProgress = kotlin.math.abs(progress)

            // Left indicator (trash) - shown when dragging left
            if (offsetX.floatValue < -30f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE53935).copy(alpha = 0.9f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("PAPELERA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }

            // Right indicator (keep) - shown when dragging right
            if (offsetX.floatValue > 30f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.9f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("GUARDAR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }

            // The draggable card
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = offsetX.floatValue / 40f
                        translationX = offsetX.floatValue
                        val scale = 1f - (absProgress * 0.05f)
                        scaleX = scale
                        scaleY = scale
                    }
                    .pointerInput(currentIndex) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                // If dragged past threshold, confirm swipe
                                if (offsetX.floatValue < -swipeThreshold) {
                                    confirmSwipe(SwipeAction.TRASH)
                                } else if (offsetX.floatValue > swipeThreshold) {
                                    confirmSwipe(SwipeAction.KEEP)
                                } else {
                                    // Snap back
                                    offsetX.floatValue = 0f
                                }
                            },
                            onDragCancel = {
                                offsetX.floatValue = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                offsetX.floatValue += dragAmount
                            }
                        )
                    },
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(photo.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = photo.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // Gradient overlay at bottom
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                )
                            )
                    )

                    // Photo info
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = photo.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = photo.albumName,
                            color = Color(0xFFCCCCCC),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Bottom action buttons (still work as tap fallback)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 48.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = { confirmSwipe(SwipeAction.TRASH) },
                containerColor = Color(0xFFE53935),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Papelera", tint = Color.White, modifier = Modifier.size(24.dp))
            }

            FloatingActionButton(
                onClick = { confirmSwipe(SwipeAction.KEEP) },
                containerColor = Color(0xFF4CAF50),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = "Guardar", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// ─── Trash Screen ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    trashed: List<PhotoItem>,
    onRecover: (PhotoItem) -> Unit,
    onDeletePermanently: (PhotoItem) -> Unit,
    onEmptyTrash: () -> Unit,
    onBack: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            title = { Text("🗑️ Papelera (${trashed.size})", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            },
            actions = {
                if (trashed.isNotEmpty()) {
                    TextButton(onClick = { showConfirmDialog = true }) {
                        Text("Vaciar", color = Color(0xFFE53935))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
        )

        if (trashed.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🗑️", fontSize = 48.sp)
                    Text("Papelera vacía", fontSize = 18.sp, color = Color.White)
                    Text("Las fotos eliminadas aparecerán aquí", color = Color(0xFF888888))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "${trashed.size} fotos en la papelera",
                    fontSize = 14.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                trashed.forEach { photo ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(photo.uri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = photo.name,
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = photo.name, fontWeight = FontWeight.Medium, maxLines = 1, color = Color.White)
                                Text(text = photo.albumName, fontSize = 12.sp, color = Color(0xFF888888))
                            }
                            IconButton(onClick = { onRecover(photo) }) {
                                Icon(Icons.Default.RestoreFromTrash, contentDescription = "Recuperar", tint = Color(0xFF4CAF50))
                            }
                            IconButton(onClick = { onDeletePermanently(photo) }) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "Eliminar", tint = Color(0xFFE53935))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("¿Vaciar papelera?", color = Color.White) },
            text = { Text("Se eliminarán ${trashed.size} fotos de forma permanente. Esta acción no se puede deshacer.", color = Color(0xFFCCCCCC)) },
            confirmButton = {
                TextButton(onClick = {
                    onEmptyTrash()
                    showConfirmDialog = false
                }) {
                    Text("Vaciar", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancelar", color = Color.White)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFCCCCCC)
        )
    }
}

// ─── Data / Helpers ───────────────────────────────────────────────

fun fetchAllAlbums(context: Context): Pair<List<Pair<String, List<PhotoItem>>>, List<PhotoItem>> {
    val albums = mutableMapOf<String, MutableList<PhotoItem>>()
    val allPhotos = mutableListOf<PhotoItem>()

    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    )
    val cursor = context.contentResolver.query(
        collection, projection, null, null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )
    cursor?.use {
        val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val bucketCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        while (it.moveToNext()) {
            val id = it.getLong(idCol)
            val name = it.getString(nameCol) ?: "photo_$id"
            val bucket = it.getString(bucketCol) ?: "Otros"
            val uri = ContentUris.withAppendedId(collection, id)
            val photo = PhotoItem(uri = uri, name = name, albumName = bucket)
            albums.getOrPut(bucket) { mutableListOf() }.add(photo)
            allPhotos.add(photo)
        }
    }

    val albumList = albums.map { (name, photos) -> name to photos.toList() }
    return Pair(albumList, allPhotos)
}

fun fetchPhotos(context: Context, selectedAlbums: List<String>): Pair<List<Pair<String, List<PhotoItem>>>, List<PhotoItem>> {
    if (selectedAlbums.isEmpty()) return fetchAllAlbums(context)
    val all = fetchAllAlbums(context)
    val filtered = all.first.filter { it.first in selectedAlbums }
    val photos = filtered.flatMap { it.second }
    return Pair(filtered, photos)
}

fun moveToTrash(context: Context, photoUri: Uri) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_TRASHED, 1)
            }
            context.contentResolver.update(photoUri, values, null, null)
        } else {
            // Pre-R: mover archivo a carpeta privada y borrar de MediaStore
            val trashDir = File(context.getExternalFilesDir(null), ".phototinder_trash")
            if (!trashDir.exists()) trashDir.mkdirs()
            val cursor = context.contentResolver.query(photoUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            var fileName = "trash_${System.currentTimeMillis()}"
            cursor?.use {
                if (it.moveToFirst()) {
                    fileName = it.getString(0) ?: fileName
                }
            }
            val inputStream = context.contentResolver.openInputStream(photoUri) ?: return
            val outFile = File(trashDir, fileName)
            FileOutputStream(outFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            context.contentResolver.delete(photoUri, null, null)
        }
    } catch (_: Exception) {}
}

fun recoverFromTrash(context: Context, photoUri: Uri) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_TRASHED, 0)
            }
            context.contentResolver.update(photoUri, values, null, null)
        } else {
            // Pre-R: restaurar desde carpeta privada de vuelta a MediaStore
            val trashDir = File(context.getExternalFilesDir(null), ".phototinder_trash")
            val cursor = context.contentResolver.query(photoUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            var fileName = photoUri.lastPathSegment ?: return
            cursor?.use {
                if (it.moveToFirst()) {
                    fileName = it.getString(0) ?: fileName
                }
            }
            val trashedFile = File(trashDir, fileName)
            if (trashedFile.exists()) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val newUri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
                context.contentResolver.openOutputStream(newUri)?.use { output ->
                    FileInputStream(trashedFile).use { input ->
                        input.copyTo(output)
                    }
                }
                trashedFile.delete()
            }
        }
    } catch (_: Exception) {}
}

/**
 * Elimina permanentemente una foto.
 * - Android R+: devuelve un PendingIntent para confirmación del sistema (no ejecuta directamente)
 * - Pre-R: borra directamente de MediaStore y del almacenamiento físico
 * 
 * Devuelve null si borró directamente (pre-R), o un PendingIntent si necesita
 * confirmación del sistema (R+). El llamador debe lanzar el PendingIntent.
 */
fun deletePermanently(context: Context, photoUri: Uri): PendingIntent? {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: usar createDeleteRequest para confirmación del sistema
            return MediaStore.createDeleteRequest(
                context.contentResolver,
                listOf(photoUri)
            )
        } else {
            // Pre-R: borrar directamente
            context.contentResolver.delete(photoUri, null, null)
            // También intentar borrar el archivo físico
            try {
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                val cursor = context.contentResolver.query(photoUri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val path = it.getString(0)
                        if (path != null) File(path).delete()
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    } catch (e: Exception) {
        return null
    }
}
