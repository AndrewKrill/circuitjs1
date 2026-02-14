/*    
    Copyright (C) Paul Falstad and Iain Sharp
    
    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.util.Locale;

// PIN diode implementation
// At low frequencies, behaves like a standard diode
// At high frequencies, acts as a variable resistor inversely proportional to DC bias current
class PINDiodeElm extends DiodeElm {
    static String lastPINModelName = "default-pin";
    double carrierLifetime = 1e-6; // typical carrier lifetime in seconds (1 microsecond)
    double intrinsicWidth = 10e-6; // width of intrinsic region in meters (10 micrometers)
    double junctionCapacitance = 1e-12; // junction capacitance in farads (1 picofarad)
    double reverseRecoveryTime = 1e-6; // reverse recovery time in seconds (1 microsecond)
    
    // Capacitance model variables (similar to VaractorElm)
    double capacitance, capCurrent;
    double compResistance, capvoltdiff;
    
    // Reverse recovery tracking
    double storedCharge = 0; // stored charge in the intrinsic region
    double lastCurrent = 0; // current from previous timestep
    boolean wasForwardBiased = false;
    
    public PINDiodeElm(int xx, int yy) {
        super(xx, yy);
        modelName = lastPINModelName;
        setup();
    }
    
    public PINDiodeElm(int xa, int ya, int xb, int yb, int f,
                    StringTokenizer st) {
        super(xa, ya, xb, yb, f, st);
        if ((f & FLAG_MODEL) == 0) {
            modelName = "default-pin";
        }
        try {
            carrierLifetime = new Double(st.nextToken()).doubleValue();
            intrinsicWidth = new Double(st.nextToken()).doubleValue();
            junctionCapacitance = new Double(st.nextToken()).doubleValue();
            reverseRecoveryTime = new Double(st.nextToken()).doubleValue();
            capvoltdiff = new Double(st.nextToken()).doubleValue();
        } catch (Exception e) {
        }
        setup();
    }
    
    int getDumpType() { return 431; }
    
    String dump() {
        return super.dump() + " " + carrierLifetime + " " + intrinsicWidth + " " + 
               junctionCapacitance + " " + reverseRecoveryTime + " " + capvoltdiff;
    }
    
    void reset() {
        super.reset();
        capvoltdiff = 0;
        storedCharge = 0;
        lastCurrent = 0;
        wasForwardBiased = false;
    }
    
    final int hs = 8;
    Polygon poly;
    Point cathode[];
    Point intrinsicRegion[];
    
    void setPoints() {
        super.setPoints();
        calcLeads(16);
        cathode = newPointArray(2);
        intrinsicRegion = newPointArray(2);
        Point pa[] = newPointArray(2);
        interpPoint2(lead1, lead2, pa[0], pa[1], 0, hs);
        interpPoint2(lead1, lead2, cathode[0], cathode[1], 1, hs);
        // Add visual indicator for intrinsic region
        interpPoint2(lead1, lead2, intrinsicRegion[0], intrinsicRegion[1], 0.5, hs/2);
        poly = createPolygon(pa[0], pa[1], lead2);
    }
    
    void draw(Graphics g) {
        setBbox(point1, point2, hs);

        double v1 = volts[0];
        double v2 = volts[1];

        draw2Leads(g);

        // draw arrow thingy
        setPowerColor(g, true);
        setVoltageColor(g, v1);
        g.fillPolygon(poly);

        // draw thing arrow is pointing to (cathode)
        setVoltageColor(g, v2);
        drawThickLine(g, cathode[0], cathode[1]);

        // draw intrinsic region indicator (small box in middle)
        drawThickLine(g, intrinsicRegion[0], intrinsicRegion[1]);
        
        doDots(g);
        drawPosts(g);
    }
    
    // Display threshold for RF resistance (ohms)
    static final double MAX_DISPLAYABLE_RF_RESISTANCE = 1e6;
    
    // Physical constants
    static final double THERMAL_VOLTAGE_AT_300K = 0.026; // kT/q at 27°C (300.15K) in volts
    static final double MAX_CAPACITANCE = 1e-6; // Maximum capacitance to avoid numerical issues (1μF)
    static final double MIN_STORED_CHARGE = 1e-15; // Minimum charge threshold for display (1 femtocoulomb)
    
    void getInfo(String arr[]) {
        arr[0] = "PIN diode";
        arr[1] = "I = " + getCurrentText(getCurrent());
        arr[2] = "Vd = " + getVoltageText(getVoltageDiff());
        arr[3] = "P = " + getUnitText(getPower(), "W");
        arr[4] = "Carrier lifetime = " + getUnitText(carrierLifetime, "s");
        arr[5] = "Intrinsic width = " + getUnitText(intrinsicWidth, "m");
        arr[6] = "Junction capacitance = " + getUnitText(junctionCapacitance, "F");
        arr[7] = "Reverse recovery time = " + getUnitText(reverseRecoveryTime, "s");
        // Calculate and display RF resistance at current bias
        // Only show if reasonable value (filtering out very large resistances for clarity)
        double rfResistance = calculateRFResistance();
        if (rfResistance > 0 && rfResistance < MAX_DISPLAYABLE_RF_RESISTANCE)
            arr[8] = "RF resistance ≈ " + getUnitText(rfResistance, Locale.ohmString);
        // Show stored charge during reverse recovery
        if (Math.abs(storedCharge) > MIN_STORED_CHARGE)
            arr[9] = "Stored charge = " + getUnitText(Math.abs(storedCharge), "C");
    }
    
    // Calculate RF resistance based on DC bias current
    // RF resistance is inversely proportional to DC current
    // At high frequencies, the stored charge in the intrinsic region
    // doesn't have time to be swept out, making it act like a resistor
    double calculateRFResistance() {
        double dcCurrent = Math.abs(getCurrent());
        if (dcCurrent < 1e-12)
            return MAX_DISPLAYABLE_RF_RESISTANCE; // very high resistance at near-zero current
        
        // Simplified model: R_RF ≈ k / I_DC
        // This is a practical approximation of the full model R_RF ≈ (k*T/q) * (τ/(W*I_DC))
        // where k*T/q is thermal voltage, τ is carrier lifetime, W is width
        // The thermal voltage (≈26mV at room temp) and other constants are absorbed
        // into the scaling factor for simplicity, giving practical resistance values
        // when typical parameters (microseconds, micrometers) are used
        double k = carrierLifetime * intrinsicWidth * 1e6; // empirical scaling for practical ohm values
        return k / dcCurrent;
    }
    
    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return super.getEditInfo(0);
        if (n == 1)
            return new EditInfo("Carrier Lifetime (s)", carrierLifetime, 0, 0);
        if (n == 2)
            return new EditInfo("Intrinsic Width (m)", intrinsicWidth, 0, 0);
        if (n == 3)
            return new EditInfo("Junction Capacitance (F)", junctionCapacitance, 0, 0);
        if (n == 4)
            return new EditInfo("Reverse Recovery Time (s)", reverseRecoveryTime, 0, 0);
        // n >= 5: map to super's n >= 1 (buttons)
        return super.getEditInfo(n - 4);
    }
    
    public void setEditValue(int n, EditInfo ei) {
        if (n == 0) {
            super.setEditValue(0, ei);
            return;
        }
        if (n == 1) {
            if (ei.value > 0)
                carrierLifetime = ei.value;
            return;
        }
        if (n == 2) {
            if (ei.value > 0)
                intrinsicWidth = ei.value;
            return;
        }
        if (n == 3) {
            if (ei.value > 0)
                junctionCapacitance = ei.value;
            return;
        }
        if (n == 4) {
            if (ei.value > 0)
                reverseRecoveryTime = ei.value;
            return;
        }
        // n >= 5: map to super's n >= 1 (buttons)
        super.setEditValue(n - 4, ei);
    }
    
    int getShortcut() { return 0; }
    
    void setLastModelName(String n) {
        lastPINModelName = n;
    }
    
    // Override to add voltage source for capacitance model (like VaractorElm)
    int getVoltageSourceCount() { return 1; }
    int getInternalNodeCount() { return 1; }
    
    void stamp() {
        super.stamp();
        // Add voltage source for capacitance companion model
        sim.stampVoltageSource(nodes[0], nodes[2], voltSource);
        sim.stampNonLinear(nodes[2]);
    }
    
    void startIteration() {
        super.startIteration();
        // Calculate voltage-dependent capacitance
        // PIN diode has lower capacitance when reverse-biased (depletion region widens)
        // and higher capacitance when forward-biased (charge storage)
        double voltdiff = volts[0] - volts[1];
        
        if (voltdiff < 0) {
            // Reverse bias: use junction capacitance (lower value)
            capacitance = junctionCapacitance;
        } else {
            // Forward bias: add diffusion capacitance due to stored charge
            // Diffusion capacitance is proportional to carrier lifetime and current
            double diffusionCap = carrierLifetime * Math.abs(getCurrent()) / THERMAL_VOLTAGE_AT_300K;
            capacitance = junctionCapacitance + diffusionCap;
        }
        
        // Limit capacitance to reasonable values
        if (capacitance < junctionCapacitance)
            capacitance = junctionCapacitance;
        if (capacitance > MAX_CAPACITANCE)
            capacitance = MAX_CAPACITANCE;
        
        // Capacitor companion model using trapezoidal approximation
        compResistance = sim.timeStep / (2 * capacitance);
        voltSourceValue = -capvoltdiff - capCurrent * compResistance;
    }
    
    void doStep() {
        super.doStep();
        // Update capacitor companion model
        sim.stampResistor(nodes[2], nodes[1], compResistance);
        sim.updateVoltageSource(nodes[0], nodes[2], voltSource, voltSourceValue);
        
        // Track charge storage for reverse recovery
        double currentCurrent = getCurrent();
        double voltdiff = volts[0] - volts[1];
        
        if (currentCurrent > 0 && voltdiff > 0) {
            // Forward biased: accumulate charge in intrinsic region
            storedCharge += currentCurrent * sim.timeStep;
            wasForwardBiased = true;
        } else if (wasForwardBiased && voltdiff < 0) {
            // Reverse bias after forward bias: simulate reverse recovery
            // Charge takes time to be swept out
            double chargeDecayRate = 1.0 / reverseRecoveryTime;
            storedCharge -= storedCharge * chargeDecayRate * sim.timeStep;
            if (Math.abs(storedCharge) < MIN_STORED_CHARGE) {
                storedCharge = 0;
                wasForwardBiased = false;
            }
        }
        
        lastCurrent = currentCurrent;
    }
    
    void stepFinished() {
        capvoltdiff = volts[0] - volts[1];
    }
    
    void calculateCurrent() {
        super.calculateCurrent();
        current += capCurrent;
    }
    
    void setCurrent(int x, double c) { 
        capCurrent = c; 
    }
    
    double voltSourceValue;
}
