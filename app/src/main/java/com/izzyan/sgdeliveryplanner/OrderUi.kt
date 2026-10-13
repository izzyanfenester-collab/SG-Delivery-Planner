package com.izzyan.sgdeliveryplanner

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun pasteWhatsAppOrders(context: android.content.Context, currentText: String): String {
    val text=runCatching {
        val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip=clipboard.primaryClip
        if(clip!=null && clip.itemCount>0) clip.getItemAt(0).text?.toString()?.takeIf {it.isNotBlank()} else null
    }.getOrNull()
    if(text!=null) return text
    android.widget.Toast.makeText(context,"No WhatsApp order text found in clipboard.",android.widget.Toast.LENGTH_LONG).show()
    return currentText
}

@Composable fun CustomerImportDialog(vm: PlannerViewModel) {
    val context=LocalContext.current
    var raw by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<OrderImport?>(null) }
    var showFailed by remember { mutableStateOf(false) }
    var showDuplicates by remember { mutableStateOf(false) }
    var editingFailedIndex by remember { mutableStateOf<Int?>(null) }
    var editedOrderText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val capacity = (50-vm.ordersBeforeImport().size).coerceAtLeast(0)
    if (editingFailedIndex != null) {
        AlertDialog(
            onDismissRequest = { editingFailedIndex = null; editError = null },
            title = { Text("Edit failed WhatsApp order") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Correct the address or six-digit Singapore postal code. Other customer details are preserved.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = editedOrderText,
                        onValueChange = { editedOrderText = it; editError = null },
                        label = { Text("Original order text") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 235.dp),
                        minLines = 9,
                        maxLines = 15
                    )
                    editError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val current = parsed
                        val index = editingFailedIndex
                        if (current != null && index != null) {
                            val retry = retryFailedCustomerOrder(current, index, editedOrderText)
                            if (retry.updated != null) {
                                parsed = retry.updated
                                if (retry.addedOrder != null) {
                                    selected = selected + retry.addedOrder.orderId
                                    editingFailedIndex = null
                                    showFailed = retry.updated.failed > 0
                                    android.widget.Toast.makeText(context,
                                        "Corrected order added. Failed: ${retry.updated.failed}",
                                        android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    editError = retry.message
                                }
                            } else {
                                editError = retry.message
                            }
                        }
                    },
                    enabled = !vm.busy && editedOrderText.isNotBlank()
                ) { Text("RECHECK & ADD") }
            },
            dismissButton = {
                TextButton(onClick = { editingFailedIndex = null; editError = null }) {
                    Text("CANCEL")
                }
            }
        )
    } else {
    AlertDialog(onDismissRequest = { vm.importOrdersDialog = false }, title = { Text("WhatsApp Import") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (parsed == null) {
                OutlinedTextField(raw,{raw=it},label={Text("Paste full WhatsApp orders")},placeholder={Text("English / Malay / emoji templates supported")},modifier=Modifier.fillMaxWidth().height(240.dp))
                Button(onClick={raw=pasteWhatsAppOrders(context,raw)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("IMPORT")}
            }
            else {
                val result = parsed!!
                val duplicateGroups = duplicatePostalGroups(result.orders)
                val duplicateCounts = duplicateGroups.associate { it.postalCode to it.orders.size }
                if (showFailed) {
                    Text("Failed orders: ${result.failed}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.titleMedium)
                    Text("These orders were NOT imported. Check the address or postal code in the original WhatsApp message, then correct and import again.",style=MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max=340.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        items(result.failedOrders.size) { index ->
                            val failure = result.failedOrders[index]
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Text("Customer: ${failure.customerName ?: "Unknown — check original message"}",style=MaterialTheme.typography.titleSmall)
                                    Text("Reason: ${failure.reason}",color=MaterialTheme.colorScheme.error)
                                    failure.suspectedPostal?.let { Text("Possible incorrect postal: $it") }
                                    Text("Address: ${failure.address ?: "Not found"}")
                                    Text("Original WhatsApp order:",style=MaterialTheme.typography.labelMedium)
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        Text(failure.originalMessage,style=MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            editingFailedIndex = index
                                            editedOrderText = failure.originalMessage
                                            editError = null
                                        },
                                        enabled = !vm.busy,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    ) {
                                        Text("Edit text")
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick={showFailed=false}) { Text("BACK TO ORDERS") }
                } else {
                    Text("Orders found: ${result.orders.size}\nUnique postal codes: ${result.orders.map { it.postalCode }.distinct().size}\nDuplicate parcels preserved: ${duplicateGroups.sumOf { it.extraParcels }}\nFailed: ${result.failed}")
                    if (duplicateGroups.isNotEmpty()) {
                        Text(
                            "⚠ ${duplicateGroups.size} postal code(s) shared by multiple customers. Check the duplicates below; every parcel stays separate.",
                            color = Color(0xFFFFCC80),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { showDuplicates = !showDuplicates },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(if (showDuplicates) "BACK TO ALL ORDERS"
                                 else "VIEW DUPLICATE POSTALS (${duplicateGroups.size})")
                        }
                        if (showDuplicates) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(duplicateGroups, key = { it.postalCode }) { group ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                "⚠ ${group.postalCode} — ${group.orders.size} customers",
                                                color = Color(0xFFFFCC80),
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            group.orders.forEachIndexed { index, customer ->
                                                Text(
                                                    "${index + 1}. ${customer.customerName?.takeIf { it.isNotBlank() } ?: "Unknown customer"} · ${orderPrice(customer)}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (result.failed > 0) {
                        Text("⚠ ${result.failed} order(s) could not be imported. Review the failed addresses before continuing.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={showFailed=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                            Text("VIEW FAILED ORDERS (${result.failed})")
                        }
                    }
                    if (!showDuplicates) {
                    TextButton(onClick={selected=result.orders.map { it.orderId }.toSet()}) { Text("SELECT ALL") }
                    Text("$capacity spaces available. Each customer remains a separate parcel.",style=MaterialTheme.typography.bodySmall)
                    if (selected.size > capacity) Text("Select fewer orders to stay within 50 deliveries.",color=MaterialTheme.colorScheme.error)
                    LazyColumn(Modifier.heightIn(max=280.dp)) { items(result.orders,key={it.orderId}) { order ->
                        Row(
                            Modifier.fillMaxWidth().height(48.dp)
                                .toggleable(order.orderId in selected,
                                    onValueChange={checked -> selected=if(checked) selected+order.orderId else selected-order.orderId}),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Checkbox(order.orderId in selected,null)
                            Text("${order.postalCode} · ${order.customerName ?: "—"} · ${orderPrice(order)}",
                                modifier = Modifier.weight(1f),
                                style=MaterialTheme.typography.bodySmall,
                                maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            duplicateCounts[order.postalCode]?.let { count ->
                                Text("DUP ×$count", color=Color(0xFFFFCC80),
                                    style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    } }
                    }
                }
            }
        }
    },confirmButton={Button(onClick={if(parsed==null) { parsed=parseCustomerOrders(raw); raw=""; selected=parsed!!.orders.map { it.orderId }.toSet() } else vm.importOrders(parsed!!.orders.filter { it.orderId in selected },parsed!!.failed)},enabled=!vm.busy && (if(parsed==null) raw.isNotBlank() else !showFailed && selected.size in 1..capacity)) {Text(if(parsed==null) "PREVIEW" else "Use These Orders")}},dismissButton={TextButton(onClick={vm.importOrdersDialog=false}) {Text("CANCEL")}})
    }
}

/**
 * Route-level failure review: OneMap postals are mapped back to individual
 * customer IDs so the runner can edit just one order without losing the other 49.
 */
@Composable fun RoutePostalReviewDialog(vm: PlannerViewModel) {
    if (!vm.reviewFailedRoutePostals) return

    val orders = vm.planningOrders()
    var editingOrderId by remember { mutableStateOf<String?>(null) }
    var editedText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    val editing = orders.firstOrNull { it.orderId == editingOrderId }

    if (editing != null) {
        val isWhatsApp = !editing.originalMessage.isNullOrBlank()
        AlertDialog(
            onDismissRequest = { editingOrderId = null; editError = null },
            title = { Text(if (isWhatsApp) "Edit WhatsApp order" else "Edit postal code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isWhatsApp) "Correct this customer's order text, especially the address/postal. Other orders will not change."
                        else "Enter the correct six-digit Singapore postal code. The other stops will not change.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Customer: ${editing.customerName ?: "Manual postal entry"}")
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it; editError = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (isWhatsApp) 230.dp else 70.dp),
                        minLines = if (isWhatsApp) 8 else 1,
                        maxLines = if (isWhatsApp) 14 else 1,
                        label = { Text(if (isWhatsApp) "Full WhatsApp order text" else "Postal code") }
                    )
                    editError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !vm.busy && editedText.isNotBlank(),
                    onClick = {
                        val error = vm.correctFailedRoutePostalOrder(editing.orderId, editedText)
                        if (error == null) {
                            editingOrderId = null
                            editError = null
                        } else editError = error
                    }
                ) { Text("SAVE CORRECTION") }
            },
            dismissButton = {
                TextButton(onClick = { editingOrderId = null; editError = null }) { Text("CANCEL") }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = { vm.reviewFailedRoutePostals = false },
            title = { Text("Review Failed Postal Codes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Only the affected orders are listed. No parcels have been removed.",
                        style = MaterialTheme.typography.bodySmall)
                    if (vm.failedRoutePostalChecks.isEmpty()) {
                        Text("All reported postal codes have been edited. Retry optimization to verify the route.",
                            color = MaterialTheme.colorScheme.primary)
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(vm.failedRoutePostalChecks, key = { it.postalCode }) { failure ->
                            val affected = orders.filter { it.postalCode == failure.postalCode }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Postal: ${failure.postalCode}", style = MaterialTheme.typography.titleMedium)
                                    Text(failure.reason, color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("${affected.size} affected order(s)", style = MaterialTheme.typography.bodySmall)
                                    affected.forEach { order ->
                                        HorizontalDivider()
                                        Text("Customer: ${order.customerName?.takeIf { it.isNotBlank() } ?: "Manual postal entry"}",
                                            style = MaterialTheme.typography.titleSmall)
                                        Text("Address: ${order.fullAddress ?: "Postal only — no WhatsApp address"}",
                                            style = MaterialTheme.typography.bodySmall, maxLines = 4)
                                        Text("Parcel price: ${orderPrice(order)}", style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(
                                            enabled = !vm.busy,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                            onClick = {
                                                editingOrderId = order.orderId
                                                editedText = order.originalMessage ?: order.postalCode
                                                editError = null
                                            }
                                        ) { Text(if (order.originalMessage.isNullOrBlank()) "Edit postal code" else "Edit text") }
                                    }
                                }
                            }
                        }
                    }
                    Text("If the postal is correct but OneMap was unavailable, retry later. Do not guess or change a valid address just to bypass the error.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.reviewFailedRoutePostals = false
                    vm.optimize()
                }, enabled = !vm.busy) { Text("RETRY OPTIMIZE") }
            },
            dismissButton = {
                TextButton(onClick = { vm.reviewFailedRoutePostals = false }) { Text("BACK TO HOME") }
            }
        )
    }
}

@Composable fun ProofPickerHost(vm: PlannerViewModel) {
    val context=LocalContext.current
    val capture=remember(context) {ProofCameraCapture(context)}
    var pendingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOrder by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingKind by rememberSaveable { mutableStateOf<String?>(null) }
    var captureName by rememberSaveable {mutableStateOf<String?>(null)}
    fun pending()=pendingRoute?.let { route -> pendingOrder?.let { order -> pendingKind?.let { kind -> ProofRequest(route,order,ProofKind.valueOf(kind)) } } }
    fun clear() {pendingRoute=null; pendingOrder=null; pendingKind=null; captureName=null}
    fun target(request: ProofRequest) {pendingRoute=request.routeId; pendingOrder=request.orderId; pendingKind=request.kind.name; vm.proofRequest=null}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val request=pending(); clear()
        if(uri!=null && request!=null) vm.saveProof(request,uri)
    }
    val camera = rememberLauncherForActivityResult(ProofTakePicture()) { success ->
        val request=pending(); val file=capture.file(captureName); clear()
        if(success && request!=null && file!=null) vm.saveProof(request,capture.uri(file),file)
        else file?.delete()
    }
    fun gallery(request: ProofRequest) {
        target(request)
        try {picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
        catch(_: Exception) {clear(); android.widget.Toast.makeText(context,"Gallery could not be opened. Please try again.",android.widget.Toast.LENGTH_LONG).show()}
    }
    LaunchedEffect(vm.proofRequest) {vm.proofRequest?.takeIf { !it.chooseSource }?.let {gallery(it)}}
    vm.proofRequest?.takeIf {it.chooseSource}?.let {request ->
        AlertDialog(onDismissRequest={vm.proofRequest=null},title={Text("Add Proof")},text={
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={
                    target(request)
                    try {
                        val file=capture.create(); captureName=file.name
                        camera.launch(capture.uri(file))
                    } catch(_: Exception) {
                        capture.file(captureName)?.delete(); clear()
                        android.widget.Toast.makeText(context,"Camera could not be opened. Please choose from Gallery.",android.widget.Toast.LENGTH_LONG).show()
                        vm.proofRequest=request
                    }
                },enabled=!vm.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("Take Photo")}
                OutlinedButton(onClick={gallery(request)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("Choose from Gallery")}
            }
        },confirmButton={},dismissButton={TextButton(onClick={vm.proofRequest=null}) {Text("Cancel")}})
    }
}

@Composable fun CustomerDetails(vm: PlannerViewModel, stop: Stop) {
    val order=stop.order ?: return
    val context=LocalContext.current
    var choosing by remember(stop.orderId) { mutableStateOf(false) }
    var selected by remember(stop.orderId) { mutableStateOf(WhatsAppChoice.PERSONAL) }
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text("Customer : ${order.customerName ?: "—"}")
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Phone : ${displayCustomerPhone(order.phoneNumber)}",Modifier.weight(1f))
            if(normalizeCustomerPhone(order.phoneNumber)!=null) IconButton(onClick={
                val choice=vm.whatsappPreferences.default
                if(choice==null || !openCustomerWhatsApp(context,order.phoneNumber!!,choice)) {selected=choice ?: WhatsAppChoice.PERSONAL; choosing=true}
            },modifier=Modifier.size(48.dp)) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_customer_whatsapp),"Open customer's WhatsApp",tint=Color(0xFF25D366),modifier=Modifier.size(30.dp))
            }
        }
        Text("Address : ${customerAddress(stop)}")
        Text("Parcel Price : ${orderPrice(order)}")
        PaymentModeRow(order)
    }
    if(choosing) AlertDialog(onDismissRequest={choosing=false},title={Text("Choose WhatsApp")},text={
        Column {
            WhatsAppChoice.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).selectable(selected==choice,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={selected=choice}),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected==choice,null); Text(choice.label)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={if(openCustomerWhatsApp(context,order.phoneNumber!!,selected)) choosing=false},modifier=Modifier.weight(1f).heightIn(min=52.dp)) {Text("Just once")}
                Button(onClick={vm.whatsappPreferences.updateDefault(selected); if(openCustomerWhatsApp(context,order.phoneNumber!!,selected)) choosing=false},modifier=Modifier.weight(1f).heightIn(min=52.dp)) {Text("Set default")}
            }
        }
    },confirmButton={},dismissButton={TextButton(onClick={choosing=false}) {Text("Cancel")}})
}

/** Route-only action: opens this customer's original order, already drafted in WhatsApp. */
@Composable fun SendOrderMessageButton(vm: PlannerViewModel, stop: Stop) {
    val order = stop.order ?: return
    val context = LocalContext.current
    var choosing by remember(order.orderId) { mutableStateOf(false) }
    var selected by remember(order.orderId) { mutableStateOf(WhatsAppChoice.PERSONAL) }
    val draft = deliveryWhatsAppMessageDraft(stop)
    Button(
        onClick = {
            val phone = order.phoneNumber
            when {
                draft == null -> android.widget.Toast.makeText(context,
                    "Message details are missing (original WhatsApp order, parcel price or ETA). Re-import or correct this order before messaging.",
                    android.widget.Toast.LENGTH_LONG).show()
                normalizeCustomerPhone(phone) == null -> android.widget.Toast.makeText(context,
                    "Customer phone number unavailable.", android.widget.Toast.LENGTH_LONG).show()
                else -> {
                    val preferred = vm.whatsappPreferences.default
                    if (preferred == null || !openCustomerWhatsApp(context,phone!!,preferred,draft)) {
                        selected = preferred ?: WhatsAppChoice.PERSONAL
                        choosing = true
                    }
                }
            }
        },
        enabled = !vm.busy,
        modifier = Modifier.heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF156082), contentColor = Color.White)
    ) { Text("Send message", style = MaterialTheme.typography.labelLarge) }

    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("Choose WhatsApp") },
        text = {
            Column {
                WhatsAppChoice.entries.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected == choice, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { selected = choice }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected == choice, null)
                        Text(choice.label)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (openCustomerWhatsApp(context, order.phoneNumber!!, selected, requireNotNull(draft))) choosing = false
                        }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text("Just once") }
                    Button(
                        onClick = {
                            if (openCustomerWhatsApp(context, order.phoneNumber!!, selected, requireNotNull(draft))) {
                                vm.whatsappPreferences.updateDefault(selected)
                                choosing = false
                            }
                        }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text("Set default") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } }
    )
}

@Composable fun OrderProofSection(vm: PlannerViewModel, plan: Plan, stop: Stop, kind: ProofKind) {
    val order=stop.order
    val id=if(kind==ProofKind.PAYMENT) order?.paymentProofFileId else order?.proofFileId
    val timestamp=if(kind==ProofKind.PAYMENT) order?.paymentProofUploadedAt else order?.proofUploadedAt
    val title=if(kind==ProofKind.PAYMENT) "Proof of Payment" else "Proof of Delivery"
    val context=LocalContext.current
    var viewing by remember { mutableStateOf(false) }
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) { bitmap=withContext(Dispatchers.IO) { val store=OrderProofStore(context); store.file(id)?.let { store.decode(it,256) } } }
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        if(id==null) Text("No proof yet") else {
            bitmap?.let { photo -> Image(photo.asImageBitmap(),title,Modifier.fillMaxWidth().height(100.dp).clipForProof().clickable { viewing=true },contentScale=ContentScale.Crop) }
            Text(if(kind==ProofKind.PAYMENT) "✓ Payment Proof Uploaded" else "✓ Proof Uploaded")
            timestamp?.let { Text(proofTime(it),style=MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(id!=null) TextButton(onClick={viewing=true}) {Text("View Photo")}
            OutlinedButton(onClick={vm.proofRequest=ProofRequest(plan.id,stop.orderId,kind,chooseSource=vm.screen=="Delivery")},enabled=!vm.busy,modifier=Modifier.heightIn(min=52.dp)) {Text(if(id!=null) "Replace Photo" else if(kind==ProofKind.PAYMENT) "Upload Payment Proof" else "Upload Proof")}
        }
    }
    if(viewing) ProofViewer(id,title) {viewing=false}
}
private fun Modifier.clipForProof() = this.then(Modifier.clip(RoundedCornerShape(12.dp)))
fun proofTime(value: String) = runCatching { java.time.LocalDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a",java.util.Locale.ENGLISH)) }.getOrDefault(value)
@Composable private fun ProofViewer(id: String?, title: String, onClose: () -> Unit) {
    val context=LocalContext.current
    var bitmap by remember(id) {mutableStateOf<Bitmap?>(null)}
    LaunchedEffect(id) {bitmap=withContext(Dispatchers.IO) { val store=OrderProofStore(context); store.file(id)?.let {store.decode(it,2048)} }}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(PremiumNavy).padding(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {Text(title,Modifier.weight(1f),color=Color.White); TextButton(onClick=onClose) {Text("Close")}}
            bitmap?.let {Image(it.asImageBitmap(),title,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)} ?: Text("Photo unavailable.",color=Color.White)
        }
    }
}
@Composable fun OrderShareButton(stop: Stop,index: Int,enabled: Boolean) {
    val context=LocalContext.current; val scope=rememberCoroutineScope(); var sharing by remember {mutableStateOf(false)}
    OutlinedButton(onClick={sharing=true; scope.launch {
        try { val file=withContext(Dispatchers.IO) {generateSingleDeliveryReport(context,stop,index)}; context.startActivity(Intent.createChooser(singleDeliveryShareIntent(context,file),"Share Delivery Report")) }
        catch(e: kotlinx.coroutines.CancellationException) {throw e}
        catch(_: Exception) {android.widget.Toast.makeText(context,"Delivery report could not be shared. Please try again.",android.widget.Toast.LENGTH_LONG).show()}
        finally {sharing=false}
    }},enabled=enabled&&!sharing,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(sharing) "Preparing report…" else "Share Report")}
}

@Composable fun DeliveryReportPreviewHost(vm: PlannerViewModel) {
    val target = vm.deliveryReportOrderId ?: return
    val plan = vm.route ?: return
    val index = plan.stops.indexOfFirst { it.orderId == target }
    if(index < 0) return
    val stop=plan.stops[index]
    val context=LocalContext.current
    var file by remember(stop) {mutableStateOf<java.io.File?>(null)}
    var bitmap by remember(stop) {mutableStateOf<Bitmap?>(null)}
    var error by remember(stop) {mutableStateOf(false)}
    LaunchedEffect(stop) {
        try {
            val result=withContext(Dispatchers.IO) {
                val image=generateSingleDeliveryReport(context,stop,index)
                image to android.graphics.BitmapFactory.decodeFile(image.absolutePath)
            }
            file=result.first; bitmap=result.second
        } catch(e: kotlinx.coroutines.CancellationException) {throw e}
        catch(_: Exception) {error=true}
    }
    Dialog(onDismissRequest={vm.deliveryReportOrderId=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(PremiumNavy).padding(12.dp)) {
            Text("Delivery Report",color=PremiumGold,fontSize=DeliveryReportTypography.bodySp.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,maxLines=2)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                bitmap?.let { Image(it.asImageBitmap(),"Delivery Report for stop ${index+1}",Modifier.fillMaxWidth(),contentScale=ContentScale.FillWidth) }
                    ?: Text(if(error) "Report could not be prepared. Close and try Share Report again." else "Preparing report…",color=Color.White)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={vm.deliveryReportOrderId=null},modifier=Modifier.weight(1f).heightIn(min=52.dp)) {Text("Close")}
                Button(onClick={file?.let {runCatching {context.startActivity(Intent.createChooser(singleDeliveryShareIntent(context,it),"Share Delivery Report"))}.onFailure {android.widget.Toast.makeText(context,"Delivery report could not be shared. Please try again.",android.widget.Toast.LENGTH_LONG).show()}}},enabled=file!=null,modifier=Modifier.weight(1f).heightIn(min=52.dp)) {Text("Share Report")}
            }
        }
    }
}
