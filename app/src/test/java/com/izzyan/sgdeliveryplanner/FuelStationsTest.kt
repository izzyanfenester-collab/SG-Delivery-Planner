package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelStationsTest {
    @Test fun boundedQueryUsesGpsOnly() {
        val query = fuelStationQuery(GpsPoint(1.3, 103.8))
        assertTrue(query.contains("(around:5000,1.30000,103.80000)"))
        assertTrue(query.contains("\"amenity\"=\"fuel\""))
        assertTrue(query.contains("out center;"))
    }

    @Test fun closestStationsFirstIncludingWayCenter() {
        val json = """{"elements":[
          {"type":"way","id":2,"center":{"lat":1.302,"lon":103.801},"tags":{"amenity":"fuel","brand":"Fuel Two"}},
          {"type":"node","id":1,"lat":1.3001,"lon":103.8001,"tags":{"amenity":"fuel","name":"Petrol One","addr:street":"Main Road"}},
          {"type":"node","id":3,"lat":1.3002,"lon":103.8002,"tags":{"amenity":"parking","name":"Not Fuel"}},
          {"type":"node","id":4,"lat":1.9,"lon":103.8,"tags":{"amenity":"fuel","name":"Too Far"}}
        ]}"""
        val found = parseFuelStations(json, GpsPoint(1.3, 103.8))
        assertEquals(2, found.size)
        assertEquals("Petrol One", found[0].name)
        assertEquals("Main Road", found[0].address)
        assertEquals("Fuel Two", found[1].name)
        assertTrue(found[0].meters < found[1].meters)
    }

    @Test(expected = IllegalStateException::class)
    fun malformedPayloadRejected() {
        parseFuelStations("{}", GpsPoint(1.3, 103.8))
    }
}
