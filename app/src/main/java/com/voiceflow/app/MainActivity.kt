package com.voiceflow.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = PrefsManager(this)
        setContent {
            VoiceFlowPixelTheme {
                VoiceFlowMainScreen(prefs = prefs)
            }
        }
    }
}

@Composable
fun VoiceFlowPixelTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFFADC6FF),
            onPrimary = Color(0xFF002E69),
            primaryContainer = Color(0xFF1B448F),
            onPrimaryContainer = Color(0xFFD8E2FF),
            secondary = Color(0xFFBFC6DC),
            surface = Color(0xFF111318),
            surfaceContainerHigh = Color(0xFF282A2F)
        )
        else -> lightColorScheme(
            primary = Color(0xFF3B64C8),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD8E2FF),
            onPrimaryContainer = Color(0xFF001A41),
            secondary = Color(0xFF575E71),
            surface = Color(0xFFF9F9FF),
            surfaceContainerHigh = Color(0xFFE8E8EE)
        )
    }

    val pixelTypography = Typography(
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        ),
        titleLarge = MaterialTheme.typography.titleLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold
        ),
        titleMedium = MaterialTheme.typography.titleMedium.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold
        ),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = FontFamily.SansSerif
        )
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = pixelTypography,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceFlowMainScreen(prefs: PrefsManager) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var apiKeyInput by remember { mutableStateOf(prefs.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(prefs.selectedModel) }
    var availableModels by remember { mutableStateOf(prefs.availableModels) }
    var isVerifyingKey by remember { mutableStateOf(false) }
    var apiStatusMessage by remember { mutableStateOf<String?>(null) }
    var apiStatusIsError by remember { mutableStateOf(false) }

    var hasOverlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibility by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var bubbleSizeIndex by remember { mutableIntStateOf(prefs.bubbleSizeIndex) }
    var hapticsEnabled by remember { mutableStateOf(prefs.hapticsEnabled) }
    var testText by remember { mutableStateOf("") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPerm = granted
    }

    // Re-check permissions automatically whenever the user returns from Android Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPerm = Settings.canDrawOverlays(context)
                hasAccessibility = isAccessibilityServiceEnabled(context)
                hasMicPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isApiKeyDone = prefs.apiKey.isNotBlank() && selectedModel.isNotBlank()
    val completedSteps = listOf(isApiKeyDone, hasOverlayPerm, hasAccessibility, hasMicPerm).count { it }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "VoiceFlow",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = "Pixel AI Voice Dictation • $completedSteps of 4 steps ready",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Section Title: Setup Checklist
            Text(
                text = "Setup Checklist",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp)
            )

            // STEP 1: API Key & Auto Model Discovery
            StepCard(
                stepNumber = 1,
                title = "Gemini API Key (Free)",
                subtitle = if (isApiKeyDone) {
                    "Connected • Active model: $selectedModel"
                } else {
                    "Enter your free key from Google AI Studio"
                },
                isCompleted = isApiKeyDone,
                icon = Icons.Outlined.Key
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("Paste Gemini API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "Toggle key visibility"
                                )
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                isVerifyingKey = true
                                apiStatusMessage = null
                                coroutineScope.launch {
                                    val res = GeminiClient.fetchAndPickBestModel(apiKeyInput)
                                    isVerifyingKey = false
                                    if (res.success) {
                                        prefs.apiKey = apiKeyInput
                                        prefs.selectedModel = res.chosenModel
                                        prefs.availableModels = res.allModels
                                        selectedModel = res.chosenModel
                                        availableModels = res.allModels
                                        apiStatusIsError = false
                                        apiStatusMessage =
                                            "Verified! Auto-picked best free Flash model: ${res.chosenModel} (${res.allModels.size} models available)"
                                    } else {
                                        apiStatusIsError = true
                                        apiStatusMessage = res.errorMessage
                                    }
                                }
                            },
                            enabled = !isVerifyingKey && apiKeyInput.isNotBlank(),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isVerifyingKey) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Checking Models...")
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Verify & Auto-Pick Model")
                            }
                        }
                    }

                    AnimatedVisibility(visible = apiStatusMessage != null) {
                        apiStatusMessage?.let { msg ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (apiStatusIsError) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (apiStatusIsError) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // STEP 2: Overlay Permission
            StepCard(
                stepNumber = 2,
                title = "Display Over Other Apps",
                subtitle = if (hasOverlayPerm) {
                    "Overlay permission granted"
                } else {
                    "Lets VoiceFlow show the floating mic bubble when Gboard is open"
                },
                isCompleted = hasOverlayPerm,
                icon = Icons.Outlined.BubbleChart
            ) {
                if (!hasOverlayPerm) {
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Overlay Settings")
                    }
                }
            }

            // STEP 3: Accessibility Service
            StepCard(
                stepNumber = 3,
                title = "Accessibility Service",
                subtitle = if (hasAccessibility) {
                    "VoiceFlow Service is active"
                } else {
                    "Detects when Gboard opens and inserts clean text at your cursor"
                },
                isCompleted = hasAccessibility,
                icon = Icons.Outlined.AccessibilityNew
            ) {
                if (!hasAccessibility) {
                    FilledTonalButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Outlined.SettingsAccessibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable in Accessibility Settings")
                    }
                }
            }

            // STEP 4: Microphone Permission
            StepCard(
                stepNumber = 4,
                title = "Microphone Permission",
                subtitle = if (hasMicPerm) {
                    "Microphone ready for dictation"
                } else {
                    "Required so VoiceFlow can hear your voice when you tap the bubble"
                },
                isCompleted = hasMicPerm,
                icon = Icons.Outlined.Mic
            ) {
                if (!hasMicPerm) {
                    FilledTonalButton(
                        onClick = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Microphone Access")
                    }
                }
            }

            // STEP 5: Interactive Test Card
            StepCard(
                stepNumber = 5,
                title = "Test Dictation Right Here",
                subtitle = "Tap the text box below so Gboard opens, then tap the floating VoiceFlow bubble!",
                isCompleted = testText.isNotBlank(),
                icon = Icons.Outlined.CheckCircle
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = testText,
                        onValueChange = { testText = it },
                        placeholder = {
                            Text("Tap here to pop up Gboard → tap the floating bubble → try saying: 'Hey bro send it at 5 no 6 smiling emoji' or 'Tu kasa aahes'")
                        },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    )
                    if (testText.isNotBlank()) {
                        TextButton(
                            onClick = { testText = "" },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Clear test box")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Section Title: Settings
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp)
            )

            // Pixel-style Grouped Settings Card
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Model Selector Dropdown
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Gemini Flash Model",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = if (selectedModel.isNotBlank()) selectedModel else "Not selected yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            TextButton(
                                onClick = {
                                    if (prefs.apiKey.isBlank()) return@TextButton
                                    isVerifyingKey = true
                                    coroutineScope.launch {
                                        val res = GeminiClient.fetchAndPickBestModel(prefs.apiKey)
                                        isVerifyingKey = false
                                        if (res.success) {
                                            availableModels = res.allModels
                                            prefs.availableModels = res.allModels
                                            if (selectedModel !in res.allModels) {
                                                selectedModel = res.chosenModel
                                                prefs.selectedModel = res.chosenModel
                                            }
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Refresh")
                            }
                        }

                        if (availableModels.isNotEmpty()) {
                            ExposedDropdownMenuBox(
                                expanded = modelDropdownExpanded,
                                onExpandedChange = { modelDropdownExpanded = !modelDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedModel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Change Model") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = modelDropdownExpanded,
                                    onDismissRequest = { modelDropdownExpanded = false }
                                ) {
                                    availableModels.forEach { modelOption ->
                                        DropdownMenuItem(
                                            text = { Text(modelOption) },
                                            onClick = {
                                                selectedModel = modelOption
                                                prefs.selectedModel = modelOption
                                                modelDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Bubble Size Selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Floating Bubble Size",
                            style = MaterialTheme.typography.titleSmall
                        )
                        val labels = listOf("Small", "Medium", "Large")
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            labels.forEachIndexed { index, label ->
                                SegmentedButton(
                                    selected = bubbleSizeIndex == index,
                                    onClick = {
                                        bubbleSizeIndex = index
                                        prefs.bubbleSizeIndex = index
                                        VoiceFlowService.instance?.refreshSettingsLive()
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = labels.size
                                    )
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                prefs.resetBubblePosition()
                                VoiceFlowService.instance?.refreshSettingsLive()
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset Bubble Position")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Haptics Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                hapticsEnabled = !hapticsEnabled
                                prefs.hapticsEnabled = hapticsEnabled
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Subtle Haptic Vibration",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Gentle tap feedback when starting, stopping, and inserting text",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = hapticsEnabled,
                            onCheckedChange = {
                                hapticsEnabled = it
                                prefs.hapticsEnabled = it
                            }
                        )
                    }
                }
            }

            // Privacy Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Privacy First: Voice audio is processed in memory and sent only to Gemini for that single dictation. Recordings and dictated text are never saved on your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun StepCard(
    stepNumber: Int,
    title: String,
    subtitle: String,
    isCompleted: Boolean,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = if (isCompleted) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCompleted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCompleted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Done",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$stepNumber. $title",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            content()
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices =
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    for (service in enabledServices) {
        val id = service.id ?: continue
        if (id.contains(context.packageName) && id.contains("VoiceFlowService")) {
            return true
        }
    }
    return false }
