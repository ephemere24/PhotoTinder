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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
                            withContext(Dispatchers.IO) {
                                deletePermanently(context, photo.uri)
                            }
                            trashedPhotos = trashedPhotos - photo
                        }
                    },
                    onEmptyTrash = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                trashedPhotos.forEach { deletePermanently(context, it.uri) }
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
                title = { Text("Permiso denegado") },
                text = { Text("Necesitamos acceso a tus fotos. Ve a Ajustes > Permisos.") },
                confirmButton = {
                    TextButton(onClick = { showPermissionDenied = false }) {
                        Text("OK")
                    }
                }
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF333333)),
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
    hasPermission: Boolean,
    isProcessing: Boolean
) {
    val selectedCount = selectedAlbums.size
    val selectedPhotos = albums.filter { it.first in selectedAlbums }.sumOf { it.second.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📸 Photo Tinder",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasPermission) "Selecciona álbumes para revisar"
                       else "Primero necesitamos permiso para acceder a tus fotos",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

        if (!hasPermission) {
            // Permission request UI
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Acceso a fotos",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Para funcionar, Photo Tinder necesita acceder a tus fotos",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 48.dp)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Dar permiso", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (albums.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Cargando álbumes...")
                }
            }
        } else {
            // Stats chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = { },
                    label = { Text("$selectedCount álbumes") }
                )
                SuggestionChip(
                    onClick = { },
                    label = { Text("$selectedPhotos fotos") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Select/Deselect all
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSelectAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Todos", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDeselectAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ninguno", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Album scrollable list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                albums.sortedByDescending { it.second.size }.forEach { (albumName, albumPhotos) ->
                    val isSelected = albumName in selectedAlbums
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                        onClick = { onAlbumToggle(albumName) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = albumName,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${albumPhotos.size} fotos",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Start button
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
                enabled = selectedAlbums.isNotEmpty(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Empezar ($selectedPhotos fotos)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
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
        // Done screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🎉", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "¡Listo!",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("No quedan más fotos por revisar")
                if (trashedPhotos.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${trashedPhotos.size} fotos en la papelera",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
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
    val offset = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentIndex) {
        offset.floatValue = 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                text = "${currentIndex + 1} / $total",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(onClick = onTrash) {
                Icon(Icons.Default.Delete, contentDescription = "Trash", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        // Photo card with swipe
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = offset.floatValue / 30f
                        translationX = offset.floatValue
                    },
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
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
                            .height(120.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
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
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }

                    // Swipe indicators
                    val rotation = offset.floatValue / 20f
                    if (offset.floatValue < -50f) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 32.dp)
                                .graphicsLayer { rotationZ = rotation },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Red)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("PAPELERA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                    if (offset.floatValue > 50f) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 32.dp)
                                .graphicsLayer { rotationZ = rotation },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("GUARDAR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 48.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Trash button
            FloatingActionButton(
                onClick = {
                    onSwiped(SwipeAction.TRASH, photo)
                    if (currentIndex + 1 < photos.size) {
                        onIndexChange(currentIndex + 1)
                    }
                },
                containerColor = Color(0xFFE53935),
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Papelera", tint = Color.White, modifier = Modifier.size(28.dp))
            }

            // Keep button
            FloatingActionButton(
                onClick = {
                    onSwiped(SwipeAction.KEEP, photo)
                    if (currentIndex + 1 < photos.size) {
                        onIndexChange(currentIndex + 1)
                    }
                },
                containerColor = Color(0xFF4CAF50),
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Favorite, contentDescription = "Guardar", tint = Color.White, modifier = Modifier.size(28.dp))
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
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("🗑️ Papelera (${trashed.size})") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (trashed.isNotEmpty()) {
                    TextButton(onClick = { showConfirmDialog = true }) {
                        Text("Vaciar", color = Color(0xFFE53935))
                    }
                }
            }
        )

        if (trashed.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🗑️", fontSize = 48.sp)
                    Text("Papelera vacía", fontSize = 18.sp)
                    Text("Las fotos eliminadas aparecerán aquí")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Text(
                    text = "${trashed.size} fotos en la papelera",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                trashed.forEach { photo ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .padding(vertical = 4.dp)
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
                                Text(text = photo.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(text = photo.albumName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = { Text("¿Vaciar papelera?") },
            text = { Text("Se eliminarán ${trashed.size} fotos de forma permanente. Esta acción no se puede deshacer.") },
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
                    Text("Cancelar")
                }
            }
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
            val trashDir = File(context.getExternalFilesDir(null), ".phototinder_trash")
            if (!trashDir.exists()) trashDir.mkdirs()
            val inputStream = context.contentResolver.openInputStream(photoUri) ?: return
            val fileName = photoUri.lastPathSegment ?: "trash_${System.currentTimeMillis()}"
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
        }
    } catch (_: Exception) {}
}

fun deletePermanently(context: Context, photoUri: Uri) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_TRASHED, 1)
            }
            context.contentResolver.update(photoUri, values, null, null)
        }
        context.contentResolver.delete(photoUri, null, null)
    } catch (_: Exception) {}
}
