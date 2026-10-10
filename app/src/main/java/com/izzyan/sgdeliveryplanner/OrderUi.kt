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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun CustomerImportDialog(vm: PlannerViewModel) {
    var raw by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<OrderImport?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val capacity = (50-vm.planningOrders().size).coerceAtLeast(0)
    AlertDialog(onDismissRequest = { vm.importOrdersDialog = false }, title = { Text("WhatsApp Import") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (parsed == null) OutlinedTextField(raw,{raw=it},label={Text("Paste full WhatsApp orders")},modifier=Modifier.fillMaxWidth().height(240.dp))
            else {
                val result = parsed!!
                Text("Orders found: ${result.orders.size}\nPostal codes found: ${result.orders.size}\nDuplicate postals preserved: ${result.orders.size-result.orders.map { it.postalCode }.distinct().size}\nFailed: ${result.failed}")
                TextButton(onClick={selected=result.orders.map { it.orderId }.toSet()}) { Text("SELECT ALL") }
                Text("$capacity spaces available. Each customer remains a separate parcel.",style=MaterialTheme.typography.bodySmall)
                if (selected.size > capacity) Text("Select fewer orders to stay within 50 deliveries.",color=MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max=280.dp)) { items(result.orders,key={it.orderId}) { order ->
                    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).toggleable(order.orderId in selected,onValueChange={checked -> selected=if(checked) selected+order.orderId else selected-order.orderId}),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(order.orderId in selected,null)
                        Text("${order.postalCode} · ${order.customerName ?: "—"} · ${orderPrice(order)}")
                    }
                } }
            }
        }
    },confirmButton={Button(onClick={if(parsed==null) { parsed=parseCustomerOrders(raw); raw=""; selected=parsed!!.orders.map { it.orderId }.toSet() } else vm.importOrders(parsed!!.orders.filter { it.orderId in selected })},enabled=!vm.busy && (if(parsed==null) raw.isNotBlank() else selected.size in 1..capacity)) {Text(if(parsed==null) "PREVIEW" else "ADD TO ROUTE")}},dismissButton={TextButton(onClick={vm.importOrdersDialog=false}) {Text("CANCEL")}})
}

@Composable fun ProofPickerHost(vm: PlannerViewModel) {
    var pendingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOrder by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingKind by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val request = pendingRoute?.let { route -> pendingOrder?.let { order -> pendingKind?.let { kind -> ProofRequest(route,order,ProofKind.valueOf(kind)) } } }
        pendingRoute=null; pendingOrder=null; pendingKind=null
        if (uri != null && request != null) vm.saveProof(request,uri)
    }
    LaunchedEffect(vm.proofRequest) {
        vm.proofRequest?.let { request -> pendingRoute=request.routeId; pendingOrder=request.orderId; pendingKind=request.kind.name; vm.proofRequest=null; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
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
        Text("Payment Mode : ${orderPayment(order)}")
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
            OutlinedButton(onClick={vm.proofRequest=ProofRequest(plan.id,stop.orderId,kind)},enabled=!vm.busy,modifier=Modifier.heightIn(min=52.dp)) {Text(if(id!=null) "Replace Photo" else if(kind==ProofKind.PAYMENT) "Upload Payment Proof" else "Upload Proof")}
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
            Text("Delivery Report",color=PremiumGold,style=MaterialTheme.typography.headlineSmall)
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
