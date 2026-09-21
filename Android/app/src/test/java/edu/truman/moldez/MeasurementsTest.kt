package edu.truman.moldez

import edu.truman.moldez.core.*
import org.junit.Assert.*
import org.junit.Test

class MeasurementsTest {
    @Test fun windowsMatchesDesktopManualCalibration() {
        val result=Measurements.calculate(100,50,AnalysisSettings(diameterMm=90.0))
        assertEquals(93.0,result.effectiveDiameterMm,0.0)
        assertEquals(3396.45148875,result.cultureAreaMm2,0.000001)
        assertEquals(52.5,result.coveragePercent,0.0)
        assertEquals(50.0,result.rawCoveragePercent,0.0)
    }
    @Test fun fullyCoveredWindowsDishRetains105Percent() {
        assertEquals(105.0,Measurements.calculate(20,20,AnalysisSettings()).coveragePercent,0.0)
    }
    @Test fun standardModeHasNoHiddenAdjustment() {
        val result=Measurements.calculate(100,50,AnalysisSettings(diameterMm=90.0,calibration=Calibration.STANDARD))
        assertEquals(90.0,result.effectiveDiameterMm,0.0)
        assertEquals(Math.PI*45*45/2,result.cultureAreaMm2,0.000001)
        assertEquals(50.0,result.coveragePercent,0.0)
    }
    @Test(expected=IllegalArgumentException::class) fun missingDishCannotProduceMeasurement() {Measurements.calculate(0,0,AnalysisSettings())}
    @Test(expected=IllegalArgumentException::class) fun cultureMustBeInsideDish() {Measurements.calculate(10,11,AnalysisSettings())}
    @Test(expected=IllegalArgumentException::class) fun nanDiameterRejected() {Measurements.calculate(100,50,AnalysisSettings(diameterMm=Double.NaN))}
    @Test fun growthUsesActualElapsedHoursAndSortsInputs() {
        val settings=AnalysisSettings()
        fun record(id:String,time:Long,count:Long)=AnalysisRecord(id,id,time,time,"","","","",settings,Measurements.calculate(100,count,settings))
        val first=record("a",1000,10);val second=record("b",7_201_000,20)
        val result=compareAnalyses(second,first)
        assertEquals(2.0,result.hours,0.0)
        assertEquals((second.measurement.cultureAreaMm2-first.measurement.cultureAreaMm2)/2,result.areaPerHour,0.000001)
        assertEquals(10.5,result.coverageChange,0.000001)
    }
    @Test(expected=IllegalArgumentException::class) fun zeroComparisonTimeRejected() {
        val s=AnalysisSettings();val r=AnalysisRecord("a","a",1,1,"","","","",s,Measurements.calculate(100,10,s))
        compareAnalyses(r,r)
    }
}
