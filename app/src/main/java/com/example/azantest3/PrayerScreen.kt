package com.example.azantest3


import android.util.Log
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.rememberScrollState // Import this
import androidx.compose.foundation.verticalScroll     // Import this
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

val PRAYER_NAMES = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")
val PRAYER_NAMES_ARABIC = listOf("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء")

// ... (prayers3661 data - I'm omitting this for brevity as it's not directly modified)


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
    } else if (todayPrayers != null && todayPrayers.isNotEmpty() && today.before(todayPrayers[0])) {
        // Case where only today's prayers are available and it's before Fajr
        return todayPrayers[0]
    }


    // Fallback or if no next prayer is found (e.g., end of data)
    return null
}

fun remainingToNextPrayer(next: Date?): String {
    if (next == null) return "No upcoming prayer" // Handle null case better
    val distance = next.time - Date().time
    if (distance <= 0) return "Prayer time!" // Changed from "حان وقت الصلاه" for clarity

    val hours = (distance / (1000 * 60 * 60)) % 24
    val minutes = (distance / (1000 * 60)) % 60
    val seconds = (distance / 1000) % 60

    return if (hours > 0) {
        String.format(Locale("en"), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale("en"), "%02d:%02d", minutes, seconds)
    }
}

@Composable
fun useRemaining(key: Any, nextPrayerDate: Date?, onPrayerTimeReached: () -> Unit): String {
    val currentNextPrayerDate by rememberUpdatedState(nextPrayerDate)
    var remainingTimeDisplay by remember { mutableStateOf(remainingToNextPrayer(currentNextPrayerDate)) }


    LaunchedEffect(key, nextPrayerDate) { // Re-launch if key changes OR nextPrayerDate itself changes
        Log.d("useRemaining", "LaunchedEffect triggered. Next prayer: ${nextPrayerDate?.let { SimpleDateFormat("HH:mm:ss").format(it) } ?: "null"}")
        // Initial calculation when effect launches/re-launches
        remainingTimeDisplay = remainingToNextPrayer(nextPrayerDate)

    if (nextPrayerDate != null) {
        // Only start countdown if there's a future prayer
        if (nextPrayerDate.time - Date().time > 0) {
            while (isActive) { // Use isActive to ensure coroutine is still active
                val newRemaining = remainingToNextPrayer(currentNextPrayerDate)
                if (newRemaining != remainingTimeDisplay) {
                    remainingTimeDisplay = newRemaining
                }

                if (newRemaining == "Prayer time!") {
                    Log.d("useRemaining", "Prayer time reached for ${currentNextPrayerDate?.let { SimpleDateFormat("HH:mm:ss").format(it) }}. Calling onPrayerTimeReached.")
                    onPrayerTimeReached() // Notify that prayer time is reached
                    break // Exit countdown loop for this specific prayer
                }
                delay(1000) // Update every second
            }
        } else {
            // If the prayer time is already past or now when this effect starts
            if (remainingTimeDisplay != "Prayer time!") { // Avoid redundant call if already set
                Log.d("useRemaining", "Prayer time already passed for ${currentNextPrayerDate?.let { SimpleDateFormat("HH:mm:ss").format(it) }}. Calling onPrayerTimeReached.")
                remainingTimeDisplay = "Prayer time!"
                onPrayerTimeReached() // Also notify if already past, to trigger next prayer calc
            }
        }
    } else {
        remainingTimeDisplay = "No upcoming prayer"
    }
    Log.d("useRemaining", "LaunchedEffect finished or broke. Final remaining: $remainingTimeDisplay")
}
return remainingTimeDisplay
}


@Composable
fun PrayerCard(prayers: List<List<Date>>, remainingToPrayer: String, currentNextPrayerDate: Date?) {
    val (today, todayPrayers, tomorrowPrayers) = todayAndTomorrow(prayers)
    val sdf = SimpleDateFormat("h:mm", Locale("en")) // Added "a" for AM/PM if desired, adjust locale if needed
    // Determine the current and next prayer NAME for display
    // This logic needs to be robust and ideally use 'currentNextPrayerDate' if possible,
    // or re-derive it based on 'prayers' and current time.
    var nextPrayerNameDisplay: String? = null
    if (currentNextPrayerDate != null && todayPrayers != null) {
        val nextPrayerIndexInToday = todayPrayers.indexOf(currentNextPrayerDate)
        if (nextPrayerIndexInToday != -1) {
            nextPrayerNameDisplay = PRAYER_NAMES_ARABIC.getOrNull(nextPrayerIndexInToday)
        } else if (tomorrowPrayers?.getOrNull(0) == currentNextPrayerDate) {
            nextPrayerNameDisplay = "الفجر (الغد)"
        }
    }
    // Fallback logic if currentNextPrayerDate doesn't directly map or is null initially
    // This part can be tricky and might need refinement based on how nextPrayer() behaves at edges.
    if (nextPrayerNameDisplay == null && todayPrayers != null) {
        val now = Date()
        var foundCurrentPrayer = false
        for (i in todayPrayers.indices) {
            val currentPrayerTime = todayPrayers[i]
            val nextPrayerTimeInList = todayPrayers.getOrNull(i + 1)

            if (nextPrayerTimeInList != null) {
                if (now.after(currentPrayerTime) && now.before(nextPrayerTimeInList)) {
                    nextPrayerNameDisplay = PRAYER_NAMES_ARABIC.getOrNull(i + 1)
                    foundCurrentPrayer = true
                    break
                }
                if (now.time == currentPrayerTime.time) {
                    nextPrayerNameDisplay = PRAYER_NAMES_ARABIC.getOrNull(i + 1)
                    foundCurrentPrayer = true
                    break
                }
            }
        }
        if (!foundCurrentPrayer) {
            if (now.before(todayPrayers[0])) {
                nextPrayerNameDisplay = PRAYER_NAMES_ARABIC[0]
            } else if (now.after(todayPrayers.last())) {
                if (tomorrowPrayers != null && tomorrowPrayers.isNotEmpty()) {
                    nextPrayerNameDisplay = "الفجر (الغد)"
                }
            }
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {

            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(8.dp)
                    .heightIn(min = 300.dp)
            ) {
                val scrollState = rememberScrollState() // Remember the scroll state
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("ar")).format(today),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), // Adjusted font size
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 40.dp) // Increased padding
                    )

                    // Determine the current and next prayer for display
                    var nextPrayerNameDisplay: String? = null
                    if (todayPrayers != null) {
                        var foundCurrentPrayer = false
                        for (i in todayPrayers.indices) {
                            val currentPrayerTime = todayPrayers[i]
                            val nextPrayerTimeInList = todayPrayers.getOrNull(i + 1)

                            if (nextPrayerTimeInList != null) {
                                if (today.after(currentPrayerTime) && today.before(
                                        nextPrayerTimeInList
                                    )
                                ) {
                                    nextPrayerNameDisplay = PRAYER_NAMES_ARABIC.getOrNull(i + 1)
                                    foundCurrentPrayer = true
                                    break
                                }
                                if (today.time == currentPrayerTime.time) { // Exactly at a prayer time
                                    nextPrayerNameDisplay = PRAYER_NAMES_ARABIC.getOrNull(i + 1)
                                    foundCurrentPrayer = true
                                    break
                                }
                            }
                        }
                        if (!foundCurrentPrayer) {
                            if (today.before(todayPrayers[0])) { // Before Fajr
                                nextPrayerNameDisplay = PRAYER_NAMES_ARABIC[0]
                            } else if (today.after(todayPrayers.last())) { // After Isha
                                if (tomorrowPrayers != null && tomorrowPrayers.isNotEmpty()) {
                                    nextPrayerNameDisplay = "الفجر (الغد)"
                                }
                            }
                        }
                    }


                    if (remainingToPrayer.isNotBlank() && remainingToPrayer != "No upcoming prayer" && nextPrayerNameDisplay != null) {
                        Text(
                            "المتبقي على $nextPrayerNameDisplay ",
                            color = MaterialTheme.colorScheme.primary, // Changed color
                            fontSize = 30.sp, // Adjusted font size
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 4.dp) // Increased padding
                        )
                        Text(
                            remainingToPrayer,
                            color = MaterialTheme.colorScheme.primary, // Changed color
                            fontSize = 30.sp, // Adjusted font size
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 10.dp) // Increased padding
                        )
                    } else if (remainingToPrayer == "Prayer time!") {
                        Text(
                            "حان وقت الصلاة", // Or derive from nextPrayerNameDisplay
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }


                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    todayPrayers?.forEachIndexed { i, prayerTime ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp), // Adjusted padding
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                text = PRAYER_NAMES_ARABIC.getOrElse(i) { "Prayer ${i + 1}" }, // Safety for index
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

                    Spacer(modifier = Modifier.height(8.dp))

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
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.End
                            )
                            Text(
                                sdf.format(fajrTomorrow),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Start
                            )


                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp)) // Add some space before the copyright
                    Text(
                        text = "Copyright © Saadeddins  ${
                            java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "+201013410370",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary, // Changed color
                        textAlign = TextAlign.Center,
                    )
                }
            }

    }
}




@Composable
fun Prayer(viewModel: PrayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val prayerTimeEntities = viewModel.prayers.value
    val addHourOffset by viewModel.addHourOffsetSetting.collectAsState()

    val azanEnabled by viewModel.azanEnabledSetting.collectAsState()
    val showExactAlarmDialog = remember { mutableStateOf(false) }
    val context = LocalContext.current

    val prayersAsDates = remember(prayerTimeEntities, addHourOffset) {
        Log.d("PrayerComposable", "Recalculating prayersAsDates, addHourOffset: $addHourOffset, Data size: ${prayerTimeEntities.size}")
        if (prayerTimeEntities.isNotEmpty()) {
            prayerTimesToDateList(prayerTimeEntities, PRAYER_NAMES, addHourOffset)
        } else {
            emptyList() // Return empty list if no data, to avoid errors in subsequent functions
        }
    }

    var prayerTimeReachedTrigger by remember { mutableStateOf(0) }
    val currentTimeKeyForNextPrayer = remember { mutableStateOf(System.currentTimeMillis()) } // Used to force re-eval of nextPrayer

    // This will now re-calculate if prayersAsDates changes OR if prayerTimeReachedTrigger changes
    val nextPrayerDate by remember(prayersAsDates, prayerTimeReachedTrigger, currentTimeKeyForNextPrayer.value) {
        derivedStateOf { // Use derivedStateOf for more efficient recomposition
            Log.d("PrayerComposable", "Recalculating nextPrayerDate. Trigger: $prayerTimeReachedTrigger, PrayersAsDates hash: ${prayersAsDates.hashCode()}, CurrentTimeKey: ${currentTimeKeyForNextPrayer.value}")
            nextPrayer(prayersAsDates)
        }
    }
    val remaining = useRemaining(
        key = prayerTimeReachedTrigger, // Add trigger to key for useRemaining's LaunchedEffect
        nextPrayerDate = nextPrayerDate,
        onPrayerTimeReached = {
            Log.d("PrayerComposable", "onPrayerTimeReached callback! Incrementing trigger.")
            // This change will cause 'nextPrayerDate' to be re-evaluated
            prayerTimeReachedTrigger++
            // Force an update to currentTimeKey to ensure nextPrayer re-evaluates with fresh "now"
            // This is important because nextPrayer() depends on the *current* Date()
            currentTimeKeyForNextPrayer.value = System.currentTimeMillis()

        }
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp), // Added padding to the main column
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // --- START: Replacement for the Button ---
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {

    Row(


    ){
        PrayerCard(
            prayers = prayersAsDates,
            remainingToPrayer = remaining,
            currentNextPrayerDate = nextPrayerDate // Pass the currently targeted prayer
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween, // Pushes label and switch apart

    ) {
        // Determine the label based on the current state
        // "التوقيت الصيفى" (Summer Time) means offset is NOT active (it's normal time)
        // "التوقيت الشتوى" (Winter Time) means offset IS active (e.g., +1 hour applied for winter)
        // Or adjust based on your exact logic for what "addHourOffset = true" means.
        // Let's assume addHourOffset = true means "Winter Time is Active (+1 hour)"
        Text(
            text = if (addHourOffset) "التوقيت  الصيفى (مفعل)" else "التوقيت الشتوى (مفعل)", // Label text
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = addHourOffset, // The current state of the setting
            onCheckedChange = { isChecked ->
                viewModel.setAddHourOffset(isChecked) // Update the ViewModel
            }
        )
    }

    Row(

        modifier = Modifier
            .fillMaxWidth()
    ) {
        Text("تفعيل الأذان", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = azanEnabled,
            onCheckedChange = { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager =
                        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    if (!alarmManager.canScheduleExactAlarms()) {
                        showExactAlarmDialog.value =
                            true // Show dialog to request permission
                    } else {
                        viewModel.setAzanEnabled(true) // Permission already granted
                    }
                } else {
                    viewModel.setAzanEnabled(enabled) // For disabling or older SDKs
                }
            }
        )
    }





            // The PrayerCard or Text will be above the Spacer and Button

        }
    }
//    if (showExactAlarmDialog.value) {
//        AlertDialog(
//            onDismissRequest = { showExactAlarmDialog.value = false },
//            title = { Text("إذن مطلوب") },
//            text = { Text("لتنبيهات الأذان الدقيقة، يحتاج التطبيق إلى إذن لجدولة التنبيهات الدقيقة. هل تريد الانتقال إلى الإعدادات لمنح الإذن؟") },
//            confirmButton = {
//                TextButton(onClick = {
//                    showExactAlarmDialog.value = false
//                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
//                    }
//                    // After user returns, you might want to re-check and then enable if granted.
//                    // For simplicity, user might need to toggle switch again if they grant it.
//                }) { Text("الإعدادات") }
//            },
//            dismissButton = {
//                TextButton(onClick = { showExactAlarmDialog.value = false }) { Text("إلغاء") }
//            }
//        )
//    }
//
//    Spacer(modifier = Modifier.height(70.dp)) // Increased spacer

//    if (prayerTimeEntities.isNotEmpty()) {
//        if (prayersAsDates.isNotEmpty()) {
//            // Pass nextPrayerDate to PrayerCard if it needs to display the *name* of the next prayer
//            PrayerCard(
//                prayers = prayersAsDates,
//                remainingToPrayer = remaining,
//                currentNextPrayerDate = nextPrayerDate // Pass the currently targeted prayer
//            )
//        } else if (addHourOffset && prayerTimeEntities.isNotEmpty()){ // Condition for when offset is being applied
//            Text("يتم تطبيق التعديل...", textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
//        } else {
//            Text("لا توجد مواقيت صلاة متاحة لليوم أو الغد.", textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
//        }
//    } else {
//        Text("يتم تحميل مواقيت الصلاة...", textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
//    }
}