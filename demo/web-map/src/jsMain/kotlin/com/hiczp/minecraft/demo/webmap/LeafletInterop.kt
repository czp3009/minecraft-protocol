package com.hiczp.minecraft.demo.webmap

@JsModule("leaflet")
@JsNonModule
external object Leaflet {
    val CRS: dynamic
    val control: dynamic

    fun map(elementId: String, options: dynamic = definedExternally): dynamic

    fun latLng(latitude: Double, longitude: Double): dynamic

    fun point(x: Double, y: Double): dynamic

}
