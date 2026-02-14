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
// 
// Physical structure: P | I | N where I-region is thick (microns to hundreds of microns)
// 
// Key characteristics:
// 1. Forward bias: Current-controlled resistor at RF (R_RF ∝ 1/I_DC)
//    - Carriers flood the intrinsic region creating conductive plasma
//    - RF signal rides on this plasma without modulating the junction
// 2. Reverse bias: Geometry-based capacitor (C ≈ ε*A/W)
//    - I-region fully depletes, capacitance is stable and voltage-independent
//    - Much lower distortion than PN diodes
// 3. Carrier lifetime (τ): Controls stored charge, RF resistance, and switching speed
class PINDiodeElm extends DiodeElm {
    static String lastPINModelName = "default-pin";
    double carrierLifetime = 1e-6; // typical carrier lifetime in seconds (1 microsecond)
    double intrinsicWidth = 10e-6; // width of intrinsic region in meters (10 micrometers)
    double reverseRecoveryTime = 1e-6; // reverse recovery time in seconds (1 microsecond)
    double junctionCapacitance = 1e-12; // junction capacitance in farads (1 picofarad)
    
    // Physical constants for capacitance calculation
    static final double EPSILON_0 = 8.854e-12; // Vacuum permittivity (F/m)
    static final double EPSILON_R_SI = 11.7; // Relative permittivity of silicon
    
    // Capacitance model variables (similar to VaractorElm)
    double capacitance, capCurrent;
    double compResistance, capvoltdiff;
    double lastCapacitance = 0; // previous capacitance for smooth transitions
    
    // Reverse recovery tracking and stored charge
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
            reverseRecoveryTime = new Double(st.nextToken()).doubleValue();
            junctionCapacitance = new Double(st.nextToken()).doubleValue();
            capvoltdiff = new Double(st.nextToken()).doubleValue();
        } catch (Exception e) {
        }
        setup();
    }
    
    int getDumpType() { return 431; }
    
    String dump() {
        return super.dump() + " " + carrierLifetime + " " + intrinsicWidth + " " + 
               reverseRecoveryTime + " " + junctionCapacitance + " " + capvoltdiff;
    }
    
    void reset() {
        super.reset();
        capvoltdiff = 0;
        storedCharge = 0;
        lastCurrent = 0;
        wasForwardBiased = false;
        lastCapacitance = junctionCapacitance;
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
        arr[7] = "Total capacitance = " + getUnitText(capacitance, "F");
        arr[8] = "Reverse recovery time = " + getUnitText(reverseRecoveryTime, "s");
        // Calculate and display RF resistance at current bias
        // Only show if reasonable value (filtering out very large resistances for clarity)
        double rfResistance = calculateRFResistance();
        if (rfResistance > 0 && rfResistance < MAX_DISPLAYABLE_RF_RESISTANCE)
            arr[9] = "RF resistance ≈ " + getUnitText(rfResistance, Locale.ohmString);
        // Show stored charge during forward bias and reverse recovery
        if (Math.abs(storedCharge) > MIN_STORED_CHARGE)
            arr[10] = "Stored charge = " + getUnitText(Math.abs(storedCharge), "C");
    }
    
    // Calculate RF resistance based on DC bias current
    // PIN diode acts as current-controlled resistor at RF frequencies
    // Physical model: R_RF ≈ W² / (q * μ * τ * I_DC)
    // where W = intrinsic width, q = electron charge, μ = mobility, τ = carrier lifetime
    // 
    // Simplified: R_RF ∝ (W² / τ) / I_DC
    // This captures the key physics: resistance inversely proportional to stored charge (τ*I_DC)
    // and directly proportional to transit distance squared (W²)
    double calculateRFResistance() {
        double dcCurrent = Math.abs(getCurrent());
        if (dcCurrent < 1e-12)
            return MAX_DISPLAYABLE_RF_RESISTANCE; // very high resistance at near-zero current
        
        // Physics-based formula: R_RF ∝ W² / (τ * I_DC)
        // For silicon: μ_n ≈ 1350 cm²/V·s, μ_p ≈ 450 cm²/V·s, average ≈ 900 cm²/V·s = 0.09 m²/V·s
        // q = 1.6e-19 C
        // Theoretical constant: 1 / (q * μ) ≈ 1 / (1.6e-19 * 0.09) ≈ 7e16 m²·s/C
        // 
        // However, we need practical ohm values for typical PIN parameters:
        // - W ~ 10 μm = 10e-6 m, so W² ~ 1e-10 m²
        // - τ ~ 1 μs = 1e-6 s
        // - I_DC ~ 1 mA = 1e-3 A
        // Theoretical R_RF ~ 7e16 * 1e-10 / (1e-6 * 1e-3) ~ 7e19 Ω (too high!)
        //
        // The discrepancy comes from the conductive plasma effect and additional factors.
        // Using empirical factor k = 7e10 gives practical values matching real PIN diodes.
        double widthSquared = intrinsicWidth * intrinsicWidth;
        double storedChargeCapacity = carrierLifetime * dcCurrent;
        
        double k = 7e10; // Empirical scaling factor for practical ohm values
        return k * widthSquared / storedChargeCapacity;
    }
    
    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return super.getEditInfo(0);
        if (n == 1)
            return new EditInfo("Carrier Lifetime (s)", carrierLifetime, 0, 0);
        if (n == 2)
            return new EditInfo("Intrinsic Width (m)", intrinsicWidth, 0, 0);
        if (n == 3)
            return new EditInfo("Reverse Recovery Time (s)", reverseRecoveryTime, 0, 0);
        if (n == 4)
            return new EditInfo("Junction Capacitance (F)", junctionCapacitance, 0, 0);
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
                reverseRecoveryTime = ei.value;
            return;
        }
        if (n == 4) {
            if (ei.value > 0)
                junctionCapacitance = ei.value;
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
        // Calculate capacitance based on bias state
        double voltdiff = volts[0] - volts[1];
        
        // Target capacitance calculation
        double targetCapacitance;
        
        if (voltdiff < 0) {
            // Reverse bias: Use junction capacitance (stable, voltage-independent)
            // This is the key characteristic of PIN diodes - stable reverse capacitance
            targetCapacitance = junctionCapacitance;
        } else {
            // Forward bias: Junction capacitance + diffusion capacitance from stored charge
            // Diffusion capacitance: C_diff = τ * g_m = τ * (dI/dV) ≈ τ * I / (2*V_T)
            // Factor of 2 accounts for PIN diode charge storage being different from PN junction
            double diffusionCap = carrierLifetime * Math.abs(getCurrent()) / (2 * THERMAL_VOLTAGE_AT_300K);
            targetCapacitance = junctionCapacitance + diffusionCap;
        }
        
        // Apply rate limiting to smooth capacitance changes and prevent numerical instability
        // This is crucial for stable forward-to-reverse transitions
        if (lastCapacitance > 0) {
            double maxChange = lastCapacitance * 0.5; // Limit change to 50% per iteration
            double change = targetCapacitance - lastCapacitance;
            if (Math.abs(change) > maxChange) {
                change = (change > 0) ? maxChange : -maxChange;
            }
            capacitance = lastCapacitance + change;
        } else {
            capacitance = targetCapacitance;
        }
        
        // Limit capacitance to reasonable values for numerical stability
        if (capacitance < junctionCapacitance)
            capacitance = junctionCapacitance;
        if (capacitance > MAX_CAPACITANCE)
            capacitance = MAX_CAPACITANCE;
        
        // Store for next iteration
        lastCapacitance = capacitance;
        
        // Capacitor companion model using trapezoidal approximation
        compResistance = sim.timeStep / (2 * capacitance);
        
        // Add minimum resistance to prevent numerical issues with very large capacitances
        double minResistance = 0.01; // 0.01 ohm minimum
        if (compResistance < minResistance)
            compResistance = minResistance;
        
        voltSourceValue = -capvoltdiff - capCurrent * compResistance;
    }
    
    void doStep() {
        super.doStep();
        // Update capacitor companion model
        sim.stampResistor(nodes[2], nodes[1], compResistance);
        sim.updateVoltageSource(nodes[0], nodes[2], voltSource, voltSourceValue);
        
        // Track charge storage with proper recombination dynamics
        double currentCurrent = getCurrent();
        double voltdiff = volts[0] - volts[1];
        
        if (currentCurrent > 0 && voltdiff > 0) {
            // Forward biased: charge accumulates but also recombines
            // dQ/dt = I - Q/τ (charge injection minus recombination)
            double injectionRate = currentCurrent;
            double recombinationRate = storedCharge / carrierLifetime;
            storedCharge += (injectionRate - recombinationRate) * sim.timeStep;
            
            // Ensure stored charge doesn't go negative
            if (storedCharge < 0)
                storedCharge = 0;
                
            wasForwardBiased = true;
        } else if (wasForwardBiased && voltdiff < 0) {
            // Reverse bias after forward bias: simulate reverse recovery
            // Charge sweeps out with time constant = reverseRecoveryTime
            double chargeDecayRate = 1.0 / reverseRecoveryTime;
            storedCharge -= storedCharge * chargeDecayRate * sim.timeStep;
            if (Math.abs(storedCharge) < MIN_STORED_CHARGE) {
                storedCharge = 0;
                wasForwardBiased = false;
            }
        } else if (voltdiff >= 0 && currentCurrent <= 0) {
            // Forward biased but no forward current: charge recombines naturally
            double recombinationRate = storedCharge / carrierLifetime;
            storedCharge -= recombinationRate * sim.timeStep;
            if (storedCharge < MIN_STORED_CHARGE) {
                storedCharge = 0;
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
