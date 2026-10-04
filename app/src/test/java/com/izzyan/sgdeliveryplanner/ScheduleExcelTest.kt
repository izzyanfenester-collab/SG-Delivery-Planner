package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonParser
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.ceil

/** Read exports with a separate, standard Excel implementation, not the writer's XML helpers. */
class ScheduleExcelTest {
    private val headings = listOf(
        "Stop", "Postal Code", "Block", "Area", "Arrival", "Distance From Previous",
        "Base Drive", "Traffic Buffer", "Planned Travel", "Delivery", "Leave", "Cumulative KM", "Updated ETA", "Updated Finish", "Actual / Delivered At"
    )
    private val formatter = DataFormatter(Locale.US)

    private fun route(count: Int = 5): Plan {
        val postalOrder = if (count == 5) listOf("005003", "730001", "100004", "560002", "048005")
            else (1..count).map { (100000 + it).toString() }
        val places = postalOrder.mapIndexed { index, postal ->
            depot.copy(postal = postal, block = "Block ${index + 1}", area = "Area ${index + 1}")
        }
        val legs = (0..count).map { index ->
            Leg(1.25 + index * 0.5, 600.0 + index * 45, 90.0 + index * 5)
        }
        return schedule(places, legs, LocalDateTime.of(2026, 10, 3, 23, 55), 8, "Normal", emptyList())
            .copy(created = "2026-10-02T18:00", cashOnHand = "12345.67", tax = "8.35")
    }

    private fun workbook(plan: Plan): XSSFWorkbook {
        val output = ByteArrayOutputStream()
        writeDeliveryScheduleXlsx(plan, output)
        val bytes = output.toByteArray()
        assertTrue("An XLSX workbook is an Open Packaging ZIP file", bytes.size > 4)
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        return XSSFWorkbook(ByteArrayInputStream(bytes))
    }

    private fun header(sheet: XSSFSheet): Row = sheet.first { row ->
        headings.indices.all { column -> row.getCell(column)?.stringCellValue == headings[column] }
    }

    private fun summaryCell(sheet: XSSFSheet, label: String): Cell {
        val labelCell = sheet.asSequence().flatMap { it.asSequence() }.first {
            it.cellType == CellType.STRING && it.stringCellValue == label
        }
        return sheet.getRow(labelCell.rowIndex).getCell(labelCell.columnIndex + 1)
    }

    private fun assertDateTime(expected: String, cell: Cell) {
        assertEquals(CellType.NUMERIC, cell.cellType)
        assertTrue("Excel must recognize the date/time format", DateUtil.isCellDateFormatted(cell))
        assertEquals(LocalDateTime.parse(expected), cell.localDateTimeCellValue.withNano(0))
    }

    private fun assertKm(expected: Double, cell: Cell) {
        assertEquals(CellType.NUMERIC, cell.cellType)
        assertEquals(expected, cell.numericCellValue, 0.000000001)
        assertTrue("The distance format must name KM", formatter.formatCellValue(cell).contains("KM", true))
        assertTrue("Distances have a readable one-decimal format", cell.cellStyle.dataFormatString.contains("0.0"))
    }

    @Test fun exportsRealExcelWorkbookWithExactHeadersAndAllStopsInSavedOrder() {
        val plan = route()
        workbook(plan).use { book ->
            assertEquals(1, book.numberOfSheets)
            assertEquals("Delivery Schedule", book.getSheetName(0))
            val sheet = book.getSheetAt(0)
            assertEquals("Runner Route Planning - Complete Delivery Schedule", sheet.getRow(0).getCell(0).stringCellValue)
            assertTrue(sheet.asSequence().flatMap { it.asSequence() }.any {
                it.cellType == CellType.STRING && it.stringCellValue == "Runner Route Planning Route Summary"
            })
            val headers = header(sheet)
            assertEquals(headings, headings.indices.map { headers.getCell(it).stringCellValue })
            val start = sheet.getRow(headers.rowNum + 1)
            assertEquals("START", start.getCell(0).stringCellValue)
            assertEquals("Woodlands Checkpoint", start.getCell(2).stringCellValue)
            assertEquals(depot.postal, start.getCell(1).stringCellValue)
            plan.stops.forEachIndexed { index, stop ->
                val row = sheet.getRow(headers.rowNum + index + 2)
                assertEquals((index + 1).toDouble(), row.getCell(0).numericCellValue, 0.0)
                assertEquals(CellType.STRING, row.getCell(1).cellType)
                assertEquals(stop.place.postal, row.getCell(1).stringCellValue)
                assertEquals(stop.place.block, row.getCell(2).stringCellValue)
                assertEquals(stop.place.area, row.getCell(3).stringCellValue)
            }
            val end = sheet.getRow(headers.rowNum + plan.stops.size + 2)
            assertEquals("END", end.getCell(0).stringCellValue)
            assertEquals("Woodlands Checkpoint", end.getCell(2).stringCellValue)
            assertEquals(depot.postal, end.getCell(1).stringCellValue)
            assertEquals(end.rowNum, sheet.lastRowNum)
        }
    }

    @Test fun exportsFullSummaryFromSavedStatusesReviewFinishAndManualMoney() {
        val base = route()
        val plan = base.copy(
            stops = base.stops.mapIndexed { index, stop ->
                stop.copy(status = listOf("DELIVERED", "ON_HOLD", "SKIPPED", "PENDING", "DELIVERED")[index])
            },
            reviewedFinishedAt = "2026-10-04T01:34", actualCompletion = "2026-10-04T01:30"
        )
        workbook(plan).use { book ->
            val sheet = book.getSheetAt(0)
            val expectedCounts = mapOf("Total Parcel" to 5, "Delivered" to 2, "On Hold" to 1, "Skipped" to 1, "Pending" to 1)
            expectedCounts.forEach { (label, count) ->
                val cell = summaryCell(sheet, label)
                assertEquals(CellType.NUMERIC, cell.cellType)
                assertEquals(count.toDouble(), cell.numericCellValue, 0.0)
            }
            val date = summaryCell(sheet, "Date")
            assertDateTime("2026-10-03T00:00", date)
            assertDateTime(plan.start, summaryCell(sheet, "Start Time"))
            assertDateTime("2026-10-04T01:34", summaryCell(sheet, "Finish Time"))
            val success = summaryCell(sheet, "Success Rate")
            assertEquals(0.4, success.numericCellValue, 0.000000001)
            assertEquals("40.0%", formatter.formatCellValue(success))
            assertKm(plan.totalKm, summaryCell(sheet, "Total KM"))
            listOf("Cash on Hand" to 12345.67, "Tax" to 8.35).forEach { (label, amount) ->
                val cell = summaryCell(sheet, label)
                assertEquals(CellType.NUMERIC, cell.cellType)
                assertEquals(amount, cell.numericCellValue, 0.000000001)
                val displayed = formatter.formatCellValue(cell)
                assertTrue(displayed.contains("SGD"))
                assertTrue(displayed.endsWith(if (label == "Tax") ".35" else ".67"))
                assertTrue(cell.cellStyle.dataFormatString.contains("0.00"))
            }
        }
    }

    @Test fun preservesArrivalDepartureAndReturnDatesAcrossMidnightAndAllLegDistances() {
        val plan = route()
        workbook(plan).use { book ->
            val sheet = book.getSheetAt(0)
            val header = header(sheet)
            val start = sheet.getRow(header.rowNum + 1)
            assertDateTime(plan.start, start.getCell(4))
            assertDateTime(plan.start, start.getCell(10))
            assertKm(0.0, start.getCell(11))
            plan.stops.forEachIndexed { index, stop ->
                val row = sheet.getRow(header.rowNum + index + 2)
                assertDateTime(stop.arrival, row.getCell(4))
                assertDateTime(stop.leave, row.getCell(10))
                assertTrue(formatter.formatCellValue(row.getCell(4)).contains("2026"))
                assertKm(stop.leg.km, row.getCell(5))
                assertKm(stop.cumulativeKm, row.getCell(11))
                listOf(6 to stop.leg.baseSeconds, 7 to stop.leg.bufferSeconds, 8 to stop.leg.plannedSeconds).forEach { (column, seconds) ->
                    val duration = row.getCell(column)
                    assertEquals(CellType.NUMERIC, duration.cellType)
                    assertEquals(ceil(seconds / 60.0) / 1440.0, duration.numericCellValue, 0.000000001)
                    assertTrue(duration.cellStyle.dataFormatString.contains("[h]"))
                }
                val delivery = row.getCell(9)
                assertEquals(CellType.STRING, delivery.cellType)
                assertTrue(delivery.stringCellValue.contains("2026-10-04"))
                assertTrue(delivery.stringCellValue.contains("–"))
            }
            val end = sheet.getRow(header.rowNum + plan.stops.size + 2)
            assertDateTime(plan.returned, end.getCell(4))
            assertDateTime(plan.returned, end.getCell(10))
            assertKm(plan.returnLeg.km, end.getCell(5))
            assertKm(plan.totalKm, end.getCell(11))
            listOf(6 to plan.returnLeg.baseSeconds, 7 to plan.returnLeg.bufferSeconds, 8 to plan.returnLeg.plannedSeconds).forEach { (column, seconds) ->
                assertEquals(ceil(seconds / 60.0) / 1440.0, end.getCell(column).numericCellValue, 0.000000001)
            }
            assertEquals("—", start.getCell(9).stringCellValue)
            assertEquals("—", end.getCell(9).stringCellValue)
        }
    }

    @Test fun freezesBoldHeadersAndGivesEveryColumnAReadableBoundedWidth() {
        workbook(route()).use { book ->
            val sheet = book.getSheetAt(0)
            val headers = header(sheet)
            val pane = sheet.paneInformation
            assertNotNull(pane)
            assertTrue(pane.isFreezePane)
            assertEquals(headers.rowNum + 1, pane.horizontalSplitPosition.toInt())
            assertEquals(headers.rowNum + 1, pane.horizontalSplitTopRow.toInt())
            headings.indices.forEach { column ->
                val cell = headers.getCell(column)
                assertTrue(book.getFontAt(cell.cellStyle.fontIndexAsInt).bold)
                val width = sheet.getColumnWidth(column)
                assertTrue("Column $column must remain usable", width in (10 * 256)..(60 * 256))
            }
        }
    }

    @Test fun exportsAllFiftyStopsAndKeepsLiteralTextSafeWithoutCreatingFormulas() {
        val original = route(50)
        val plan = original.copy(stops = original.stops.mapIndexed { index, stop ->
            if (index == 0) stop.copy(place = stop.place.copy(
                postal = "000001", block = "=SUM(1,2)", area = "A & B <North> \"西部\" _x0041_ 🚚"
            )) else stop
        })
        workbook(plan).use { book ->
            val sheet = book.getSheetAt(0)
            val headers = header(sheet)
            assertEquals(headers.rowNum + 52, sheet.lastRowNum)
            val first = sheet.getRow(headers.rowNum + 2)
            assertEquals("000001", first.getCell(1).stringCellValue)
            assertEquals("=SUM(1,2)", first.getCell(2).stringCellValue)
            assertEquals("A & B <North> \"西部\" _x0041_ 🚚", first.getCell(3).stringCellValue)
            assertEquals(CellType.STRING, first.getCell(2).cellType)
            plan.stops.forEachIndexed { index, stop ->
                assertEquals(stop.place.postal, sheet.getRow(headers.rowNum + index + 2).getCell(1).stringCellValue)
            }
            assertFalse(sheet.asSequence().flatMap { it.asSequence() }.any { it.cellType == CellType.FORMULA })
            assertEquals("END", sheet.getRow(sheet.lastRowNum).getCell(0).stringCellValue)
        }
    }

    @Test fun exportsSavedCustomLocationAtBothBoundariesAndInSummary() {
        val custom = StartLocation("CUSTOM", 1.3012, 103.8159, "Custom Location",
            "5 Alpine Road, Singapore 000123", "000123")
        val saved = PlanJson.decode(PlanJson.encode(route().copy(startLocation = custom)))
        workbook(saved).use { book ->
            val sheet = book.getSheetAt(0)
            val headers = header(sheet)
            assertEquals("5 Alpine Road, Singapore 000123",
                summaryCell(sheet, "Start Location").stringCellValue)
            assertEquals("5 Alpine Road, Singapore 000123",
                summaryCell(sheet, "End Location").stringCellValue)
            listOf(headers.rowNum + 1 to "START", headers.rowNum + saved.stops.size + 2 to "END").forEach { (index, label) ->
                val row = sheet.getRow(index)
                assertEquals(label, row.getCell(0).stringCellValue)
                assertEquals(CellType.STRING, row.getCell(1).cellType)
                assertEquals("000123", row.getCell(1).stringCellValue)
                assertEquals("5 Alpine Road, Singapore 000123", row.getCell(2).stringCellValue)
                assertEquals("Custom Location", row.getCell(3).stringCellValue)
            }
            assertEquals(saved.stops.map { it.place.postal }, saved.stops.indices.map {
                sheet.getRow(headers.rowNum + it + 2).getCell(1).stringCellValue
            })
            assertKm(saved.returnLeg.km, sheet.getRow(sheet.lastRowNum).getCell(5))
            assertKm(saved.totalKm, sheet.getRow(sheet.lastRowNum).getCell(11))
        }
    }

    @Test fun customCoordinatesWithoutResolvedAddressRemainReadableInExport() {
        val location = StartLocation("CUSTOM", 1.3012, 103.8159, "Custom Location")
        val plan = route().copy(startLocation = location)
        workbook(plan).use { book ->
            val sheet = book.getSheetAt(0)
            val headers = header(sheet)
            assertEquals("Custom Location", summaryCell(sheet, "Start Location").stringCellValue)
            assertEquals("Custom Location", summaryCell(sheet, "End Location").stringCellValue)
            listOf(headers.rowNum + 1, sheet.lastRowNum).forEach { index ->
                assertEquals("Custom Location", sheet.getRow(index).getCell(2).stringCellValue)
                assertEquals("", sheet.getRow(index).getCell(1).stringCellValue)
            }
        }
    }

    @Test fun exportsPersistedHomeSnapshotAndLeadingZeroPostalAtBothBoundaries() {
        val home = StartLocation("HOME", 1.3234, 103.9234, "Home",
            "7 Ocean Road, Singapore 000007", "000007")
        val saved = PlanJson.decode(PlanJson.encode(route().copy(startLocation = home)))
        workbook(saved).use { book ->
            val sheet = book.getSheetAt(0)
            val headers = header(sheet)
            assertEquals("Home — 7 Ocean Road, Singapore 000007", summaryCell(sheet, "Start Location").stringCellValue)
            assertEquals("Home — 7 Ocean Road, Singapore 000007", summaryCell(sheet, "End Location").stringCellValue)
            listOf(headers.rowNum + 1 to "START", headers.rowNum + saved.stops.size + 2 to "END").forEach { (index, label) ->
                val row = sheet.getRow(index)
                assertEquals(label, row.getCell(0).stringCellValue)
                assertEquals(CellType.STRING, row.getCell(1).cellType)
                assertEquals("000007", row.getCell(1).stringCellValue)
                assertEquals("Home — 7 Ocean Road, Singapore 000007", row.getCell(2).stringCellValue)
                assertEquals("Home", row.getCell(3).stringCellValue)
            }
            assertDateTime(saved.start, sheet.getRow(headers.rowNum + 1).getCell(4))
            assertDateTime(saved.returned, sheet.getRow(sheet.lastRowNum).getCell(4))
            assertEquals(saved.stops.map { it.place.postal }, saved.stops.indices.map {
                sheet.getRow(headers.rowNum + it + 2).getCell(1).stringCellValue
            })
        }
    }

    @Test fun legacyRoutesWithoutStoredStartLocationStillExportWoodlandsBothWays() {
        val legacy = JsonParser.parseString(PlanJson.encode(route())).asJsonObject.apply { remove("startLocation") }
        val restored = PlanJson.decode(legacy.toString())
        workbook(restored).use { book ->
            val sheet = book.getSheetAt(0)
            val headers = header(sheet)
            assertEquals("Woodlands Checkpoint — 21 Woodlands Crossing, Singapore 738203",
                summaryCell(sheet, "Start Location").stringCellValue)
            assertEquals("Woodlands Checkpoint — 21 Woodlands Crossing, Singapore 738203",
                summaryCell(sheet, "End Location").stringCellValue)
            listOf(headers.rowNum + 1, sheet.lastRowNum).forEach { index ->
                assertEquals("Woodlands Checkpoint", sheet.getRow(index).getCell(2).stringCellValue)
                assertEquals("738203", sheet.getRow(index).getCell(1).stringCellValue)
            }
            assertEquals(restored.stops.size.toDouble(), summaryCell(sheet, "Total Parcel").numericCellValue, 0.0)
        }
    }

    @Test fun exportsActualAndUpdatedTimesWithoutChangingPlannedTimesOrOrder() {
        val base = route().copy(tax = "27.72", exchangeRate = "3.60", remark = "Paid in SGD")
        val plan = reviewStop(base, "DELIVERED", "2026-10-04T00:25:37")
        workbook(plan).use { book ->
            val sheet = book.getSheetAt(0)
            val row = header(sheet).rowNum
            assertDateTime("2026-10-04T00:25:37", sheet.getRow(row + 2).getCell(14))
            assertDateTime(requireNotNull(plan.stops[1].etaArrival), sheet.getRow(row + 3).getCell(12))
            assertDateTime(requireNotNull(plan.stops[1].etaLeave), sheet.getRow(row + 3).getCell(13))
            assertDateTime(base.stops[1].arrival, sheet.getRow(row + 3).getCell(4))
            assertEquals("RM 99.79", summaryCell(sheet, "Tax MYR").stringCellValue)
            assertEquals("3.60", summaryCell(sheet, "Rate").stringCellValue)
            assertEquals("Paid in SGD", summaryCell(sheet, "Remark").stringCellValue)
        }
    }

    @Test fun filenameUsesRouteStartDateRatherThanCreationOrExportDate() {
        assertEquals("Runner_Route_Planning_2026-10-03.xlsx", deliveryExcelFileName(route()))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", EXCEL_MIME_TYPE)
    }
}
