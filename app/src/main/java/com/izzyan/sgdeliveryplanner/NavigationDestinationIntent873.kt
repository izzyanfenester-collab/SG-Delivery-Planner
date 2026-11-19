package com.izzyan.sgdeliveryplanner

import android.content.Intent

internal const val NAV_DELIVERY_ADDRESS_873="delivery_address"
internal const val NAV_DELIVERY_ORDER_873="delivery_order_id"

/** Route identity excludes unit/order/address labels, so a different order at
 * the same destination updates its heading without restarting GPS guidance. */
internal data class NavigationSessionKey873(val postal:String,val lat:Double,val lon:Double)
internal data class NavigationDestinationSnapshot873(
    val place:Place,val deliveryAddress:String?,val orderId:String?
) {
    val sessionKey get()=NavigationSessionKey873(place.postal,place.lat,place.lon)
    val heading get()=destinationHeading873(place,deliveryAddress)
}

internal fun selectedDeliveryAddress873(stop:Stop):String =
    if(stop.order!=null)stop.order.fullAddress.orEmpty() else stop.place.address

internal fun navigationDestinationSnapshot873(intent:Intent):NavigationDestinationSnapshot873? {
    val postal=intent.getStringExtra("postal")?:return null
    return NavigationDestinationSnapshot873(
        Place(postal,intent.getDoubleExtra("lat",Double.NaN),intent.getDoubleExtra("lon",Double.NaN),
            intent.getStringExtra("block").orEmpty(),"",intent.getStringExtra("address").orEmpty()),
        intent.getStringExtra(NAV_DELIVERY_ADDRESS_873),intent.getStringExtra(NAV_DELIVERY_ORDER_873))
}
