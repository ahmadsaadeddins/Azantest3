package com.example.azantest3


import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material3.Switch // For the toggle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.rememberScrollState // Import this
import androidx.compose.foundation.verticalScroll     // Import this
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.azantest3.datastore.PRAYER_NAMES
import com.example.azantest3.datastore.PRAYER_NAMES_ARABIC
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType



fun convertTimeToDateTime(month: String, day: Int, year: Int, time: String): Date {
    val format =  SimpleDateFormat("MMM dd yyyy HH:mm:ss", Locale.ENGLISH)
    // Log.d("ConvertTime", "Parsing: $month $day $year $time")
    return format.parse("$month $day $year $time")!!
}

// MODIFIED prayerTimesToDateList
fun prayerTimesToDateList(
    data: List<PrayerTime>, // Changed from List<Map<String, Any>> to List<PrayerTime>
    prayerNames: List<String>,
    addHourOffset: Boolean // New parameter
): List<List<Date>> {
    val year = Calendar.getInstance().get(Calendar.YEAR)
    Log.d("PrayerTimesToDateList", "Processing ${data.size} prayer times. Offset: $addHourOffset")

    return data.map { today -> // 'today' is now a PrayerTime object
        prayerNames.map { key ->
            // Get the time string from the PrayerTime object based on the key
            val timeString = when (key) {
                "fajr" -> today.fajr
                "sunrise" -> today.sunrise
                "dhuhr" -> today.dhuhr // Assuming your PrayerTime entity uses 'dhuhr'
                "asr" -> today.asr
                "maghrib" -> today.maghrib
                "isha" -> today.isha
                else -> error("Invalid prayer name: $key")
            }

            // Get month and day from the PrayerTime object
            val monthName = today.month_name
            val dayOfMonth = today.day

            var originalDate = convertTimeToDateTime(
                monthName,
                dayOfMonth,
                year,
                timeString
            )

            if (addHourOffset) {
                val calendar = Calendar.getInstance()
                calendar.time = originalDate
                calendar.add(Calendar.HOUR_OF_DAY, 1)
                originalDate = calendar.time
                // Log.d("PrayerTimesToDateList", "Offset applied to $key: New time ${SimpleDateFormat("HH:mm:ss").format(originalDate)}")
            }
            originalDate
        }
    }
}


fun todayAndTomorrow(data: List<List<Date>>): Triple<Date, List<Date>?, List<Date>?> {
    val today = Date()
    val cal = Calendar.getInstance()
    cal.time = today
    val todayStr = SimpleDateFormat("MM-dd-yyyy", Locale.US).format(today)

    cal.add(Calendar.DATE, 1)
    val tomorrow = cal.time
    val tomorrowStr = SimpleDateFormat("MM-dd-yyyy", Locale.US).format(tomorrow)

    var todayPrayers: List<Date>? = null
    var tomorrowPrayers: List<Date>? = null

    for (dayPrayers in data) { // Renamed 'day' to 'dayPrayers' to avoid conflict if 'day' is a var
        if (dayPrayers.isNotEmpty()) {
            val dateStr = SimpleDateFormat("MM-dd-yyyy", Locale.US).format(dayPrayers[0])
            if (dateStr == todayStr) todayPrayers = dayPrayers
            if (dateStr == tomorrowStr) tomorrowPrayers = dayPrayers
        }
    }
    // Log.d("TodayAndTomorrow", "Today: $todayStr, Today Prayers Found: ${todayPrayers != null}")
    // Log.d("TodayAndTomorrow", "Tomorrow: $tomorrowStr, Tomorrow Prayers Found: ${tomorrowPrayers != null}")
    return Triple(today, todayPrayers, tomorrowPrayers)
}

fun nextPrayer(data: List<List<Date>>): Date? {
    val (today, todayPrayers, tomorrowPrayers) = todayAndTomorrow(data)

    todayPrayers?.forEachIndexed { i, prayer ->
        // Ensure there is a next prayer in today's list
        if (i < todayPrayers.size - 1) {
            val nextInToday = todayPrayers[i + 1]
            if (today.after(prayer) && today.before(nextInToday)) {
                return nextInToday
            }
            // Edge case: If current time is exactly the prayer time
            if (today.time == prayer.time) {
                return nextInToday
            }
        }
    }

    if (todayPrayers != null && tomorrowPrayers != null) {
        // After Isha today, next prayer is Fajr tomorrow
        if (todayPrayers.size > 5 && today.after(todayPrayers[5])) { // Assuming Isha is at index 5
            if (tomorrowPrayers.isNotEmpty()) {
                return tomorrowPrayers[0] // Fajr tomorrow
            }
        }
        // Before Fajr today, next prayer is Fajr today
        if (todayPrayers.isNotEmpty() && today.before(todayPrayers[0])) {
            return todayPrayers[0] // Fajr today
        }
    } else if (!todayPrayers.isNullOrEmpty() && today.before(todayPrayers[0])) {
        // Case where only today's prayers are available and it's before Fajr
        return todayPrayers[0]
    }


    // Fallback or if no next prayer is found (e.g., end of data)
    return null
}

//fun remainingToNextPrayer(next: Date?): String {
//    if (next == null) return "No upcoming prayer" // Handle null case better
//    val distance = next.time - Date().time
//    if (distance <= 0) return "Prayer time!" // Changed from "حان وقت الصلاه" for clarity
//
//    val hours = (distance / (1000 * 60 * 60)) % 24
//    val minutes = (distance / (1000 * 60)) % 60
//    val seconds = (distance / 1000) % 60
//
//    return if (hours > 0) {
//        String.format(Locale("en"), "%02d:%02d:%02d", hours, minutes, seconds)
//    } else {
//        String.format(Locale("en"), "%02d:%02d", minutes, seconds)
//    }
//}

//fun getRemainingPrayerAndIqamaTime(
//    prayerTime: Date,
//    nextPrayerTime: Date?,
//    prayerName: String,
//    iqamaOffsetMinutes: Int
//): Triple<String, String?, Boolean> {
//    val now = Date()
//
//    val prayerMillisLeft = prayerTime.time - now.time
//    val iqamaTime = Date(prayerTime.time + iqamaOffsetMinutes * 60_000)
//    val iqamaMillisLeft = iqamaTime.time - now.time
//
//    val prayerRemaining = if (prayerMillisLeft > 0) formatDuration(prayerMillisLeft) else "حان وقت الصلاة"
//    val iqamaRemaining = if (iqamaMillisLeft > 0) formatDuration(iqamaMillisLeft) else null
//
//    val isWithinFirst15 = iqamaMillisLeft > 0 && prayerMillisLeft > 0
//
//    return Triple(prayerRemaining, iqamaRemaining, isWithinFirst15)
//}

@SuppressLint("DefaultLocale")
fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0)
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    else
        String.format("%02d:%02d", minutes, seconds)
}


//@Composable
//fun useRemaining(key: Any, nextPrayerDate: Date?, onPrayerTimeReached: () -> Unit): String {
//    var remainingTimeDisplay by remember { mutableStateOf(remainingToNextPrayer(nextPrayerDate)) }
//
//    LaunchedEffect(key, nextPrayerDate) {
//        Log.d("useRemaining", "⏳ Countdown started for: ${nextPrayerDate?.let { formatTime(it) } ?: "null"}")
//
//        if (nextPrayerDate == null) {
//            remainingTimeDisplay = "No upcoming prayer"
//            return@LaunchedEffect
//        }
//
//        if (nextPrayerDate.time <= Date().time) {
//            remainingTimeDisplay = "Prayer time!"
//            Log.d("useRemaining", "Prayer time already passed for: ${formatTime(nextPrayerDate)}")
//            onPrayerTimeReached()
//            return@LaunchedEffect
//        }
//
//        while (isActive) {
//            val now = Date()
//            val newRemaining = remainingToNextPrayer(nextPrayerDate)
//
//            if (newRemaining != remainingTimeDisplay) {
//                remainingTimeDisplay = newRemaining
//            }
//
//            if (nextPrayerDate.time <= now.time) {
//                Log.d("useRemaining", "🔔 Prayer time reached at: ${formatTime(nextPrayerDate)}")
//                remainingTimeDisplay = "Prayer time!"
//                onPrayerTimeReached()
//                break
//            }
//
//            delay(1000)
//        }
//
//        Log.d("useRemaining", "🛑 Countdown finished. Final: $remainingTimeDisplay")
//    }
//
//    return remainingTimeDisplay
//}


@Composable
fun useRemaining(
    key: Any,
    nextPrayerDate: Date?,
    prayerIndex: Int,
    viewModel: PrayerViewModel,
    onPrayerTimeReached: () -> Unit
): Triple<String, String?, Boolean> {
//    val currentNextPrayerDate by rememberUpdatedState(nextPrayerDate)
    var prayerRemainingTime by remember { mutableStateOf("--:--") }
    var iqamaRemainingTime by remember { mutableStateOf<String?>(null) }
    var isWithinIqama by remember { mutableStateOf(false) }

    LaunchedEffect(key, nextPrayerDate) {
        if (nextPrayerDate == null) {
            prayerRemainingTime = "No upcoming prayer"
            return@LaunchedEffect
        }

        val iqamaOffset = viewModel.getIqamaOffsetForPrayer(prayerIndex)
        val iqamaTime = Date(nextPrayerDate.time + (iqamaOffset * 60 * 1000))

        while (isActive) {
            val now = Date()

            when {
                now.before(nextPrayerDate) -> {
                    // Before prayer time
                    val remaining = nextPrayerDate.time - now.time
                    prayerRemainingTime = formatDuration(remaining)
                    iqamaRemainingTime = null
                    isWithinIqama = false
                }
                now.before(iqamaTime) -> {
                    // Between prayer time and iqama
                    prayerRemainingTime = "حان وقت الصلاة"
                    val iqamaRemaining = iqamaTime.time - now.time
                    iqamaRemainingTime = formatDuration(iqamaRemaining)
                    isWithinIqama = true
                }
                else -> {
                    // After iqama time
                    prayerRemainingTime = "حان وقت الصلاة"
                    iqamaRemainingTime = null
                    isWithinIqama = false
                    onPrayerTimeReached()
                    break
                }
            }

            delay(1000)
        }
    }

    return Triple(prayerRemainingTime, iqamaRemainingTime, isWithinIqama)
}
//fun formatTime(date: Date?): String {
//    if (date == null) return "Invalid time"
//    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
//    return sdf.format(date)
//}

@Composable
fun PrayerCard(prayers: List<List<Date>>, remainingToPrayer: Triple<String, String?, Boolean>, currentNextPrayerDate: Date?) {
    val (today, todayPrayers, tomorrowPrayers) = todayAndTomorrow(prayers)
    val sdf = SimpleDateFormat("h:mm", Locale("en"))
    val scrollState = rememberScrollState()

    // Get next prayer name
    var nextPrayerNameDisplay: String? = null
    if (currentNextPrayerDate != null && todayPrayers != null) {
        val nextPrayerIndexInToday = todayPrayers.indexOf(currentNextPrayerDate)
        if (nextPrayerIndexInToday != -1) {
            nextPrayerNameDisplay = PRAYER_NAMES_ARABIC.getOrNull(nextPrayerIndexInToday)
        } else if (tomorrowPrayers?.getOrNull(0) == currentNextPrayerDate) {
            nextPrayerNameDisplay = "الفجر (الغد)"
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(top = 26.dp, start = 10.dp, end = 10.dp)
                .heightIn(min = 300.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Date Display
                Text(
                    text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("ar")).format(today),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                val (prayerTimeRemaining, iqamaTimeRemaining) = remainingToPrayer

                // Countdown Display Section
                when {
                    // During Iqama Period
                    iqamaTimeRemaining != null -> {
                        Text(
                            "حان وقت الصلاة",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            "المتبقي على الإقامة",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            iqamaTimeRemaining,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                    // Normal Prayer Countdown
                    prayerTimeRemaining != "حان وقت الصلاة" && nextPrayerNameDisplay != null -> {
                        Text(
                            "المتبقي على $nextPrayerNameDisplay",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            prayerTimeRemaining,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                    // Prayer Time Announcement
                    prayerTimeRemaining == "حان وقت الصلاة" -> {
                        Text(
                            "حان وقت الصلاة",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Prayer Times List
                todayPrayers?.forEachIndexed { i, prayerTime ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = PRAYER_NAMES_ARABIC.getOrElse(i) { "Prayer ${i + 1}" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = sdf.format(prayerTime),
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Tomorrow's Fajr
                tomorrowPrayers?.getOrNull(0)?.let { fajrTomorrow ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "فجر الغد",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            sdf.format(fajrTomorrow),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Copyright Section
                Text(
                    text = "Copyright © Saadeddins ${Calendar.getInstance().get(Calendar.YEAR)}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "+201013410370",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}



@Composable
fun IqamaSettingsScreen(
    viewModel: PrayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onNavigateBack: () -> Unit // Add navigation callback
) {
    val iqamaSettings by viewModel.iqamaSettings.collectAsState(initial = IqamaSettings())
    val scrollState = rememberScrollState()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Bar with Back Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع"
                    )
                }
                Text(
                    "إعدادات الإقامة وصلاة الضحى",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                // Empty box to balance the layout
                Box(modifier = Modifier.width(48.dp))
            }

            // Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                // Your existing IqamaSettingItems
                IqamaSettingItem(
                    prayerName = "الفجر",
                    currentMinutes = iqamaSettings.fajrIqama,
                    onMinutesChange = { viewModel.updateIqamaSetting("fajr", it) }
                )

                // Duha Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            "وقت صلاة الضحى",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "يبدأ وقت صلاة الضحى بعد شروق الشمس",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        IqamaSettingItem(
                            prayerName = "شروق الشمس",
                            currentMinutes = iqamaSettings.sunriseOffset,
                            onMinutesChange = { viewModel.updateIqamaSetting("sunrise", it) },
                            subtitle = "دقائق بعد شروق الشمس"
                        )
                    }
                }

                // Regular prayer iqama settings
                listOf(
                    Triple("الظهر", iqamaSettings.dhuhrIqama, "dhuhr"),
                    Triple("العصر", iqamaSettings.asrIqama, "asr"),
                    Triple("المغرب", iqamaSettings.maghribIqama, "maghrib"),
                    Triple("العشاء", iqamaSettings.ishaIqama, "isha")
                ).forEach { (prayerName, minutes, key) ->
                    IqamaSettingItem(
                        prayerName = prayerName,
                        currentMinutes = minutes,
                        onMinutesChange = { viewModel.updateIqamaSetting(key, it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Bottom Button
            FilledTonalButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Text("العودة إلى مواقيت الصلاة")
            }
        }
    }
}

@Composable
fun IqamaSettingItem(
    prayerName: String,
    currentMinutes: Int,
    onMinutesChange: (Int) -> Unit,
    subtitle: String? = null
) {
    var showDialog by remember { mutableStateOf(false) }
    var tempMinutes by remember(currentMinutes) { mutableStateOf(currentMinutes.toString()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = prayerName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "$currentMinutes دقيقة",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FilledTonalButton(
                        onClick = { showDialog = true },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text("تعديل")
                    }
                }
            }
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    "تعديل وقت الإقامة",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = tempMinutes,
                        onValueChange = { input ->
                            tempMinutes = input.filter { it.isDigit() }.take(2)
                        },
                        label = { Text("الدقائق") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        tempMinutes.toIntOrNull()?.let { minutes ->
                            if (minutes in 0..60) {
                                onMinutesChange(minutes)
                                showDialog = false
                            }
                        }
                    }
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
fun Prayer(
    viewModel: PrayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onNavigateToIqamaSettings: () -> Unit // Add navigation callback
) {
    val prayerTimeEntities = viewModel.prayers.value
    val addHourOffset by viewModel.addHourOffsetSetting.collectAsState()
    val azanEnabled by viewModel.azanEnabledSetting.collectAsState()
    val (  rt , todayPrayers, _) = todayAndTomorrow(if (prayerTimeEntities.isNotEmpty()) {
        prayerTimesToDateList(prayerTimeEntities, PRAYER_NAMES, addHourOffset)
    } else emptyList())

    val prayersAsDates = remember(prayerTimeEntities, addHourOffset) {
        if (prayerTimeEntities.isNotEmpty()) {
            prayerTimesToDateList(prayerTimeEntities, PRAYER_NAMES, addHourOffset)
        } else {
            emptyList()
        }
    }

    var prayerTimeReachedTrigger by remember { mutableIntStateOf(0) }
    val currentTimeKeyForNextPrayer = remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Find current prayer index
    val (nextPrayerDate, currentPrayerIndex) = remember(prayersAsDates, prayerTimeReachedTrigger, currentTimeKeyForNextPrayer.longValue) {
        derivedStateOf {
            val next = nextPrayer(prayersAsDates)
            val index = todayPrayers?.indexOf(next) ?: -1
            Pair(next, index)
        }
    }.value

    val remaining = useRemaining(
        key = prayerTimeReachedTrigger,
        nextPrayerDate = nextPrayerDate,
        prayerIndex = currentPrayerIndex,
        viewModel = viewModel,
        onPrayerTimeReached = {
            prayerTimeReachedTrigger++
            currentTimeKeyForNextPrayer.longValue = System.currentTimeMillis()
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            PrayerCard(
                prayers = prayersAsDates,
                remainingToPrayer = remaining,
                currentNextPrayerDate = nextPrayerDate
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Time Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (addHourOffset) "التوقيت الصيفى (مفعل)" else "التوقيت الشتوى (مفعل)",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = addHourOffset,
                    onCheckedChange = { isChecked ->
                        viewModel.setAddHourOffset(isChecked)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Azan Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("تفعيل الأذان", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = azanEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setAzanEnabled(enabled)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Iqama Settings Button
            FilledTonalButton(
                onClick = onNavigateToIqamaSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("إعدادات الإقامة")
            }
        }
    }
}

@Composable
fun PrayerNavigation() {
    val navController = rememberNavController()

    NavHost(navController, startDestination = "prayer") {
        composable("prayer") {
            Prayer(
                onNavigateToIqamaSettings = {
                    navController.navigate("iqama_settings")
                }
            )
        }
        composable("iqama_settings") {
            IqamaSettingsScreen(
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }
    }
}