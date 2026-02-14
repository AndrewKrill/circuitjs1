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
        } catch (Exception e) {
        }
        setup();
    }
    
    int getDumpType() { return 431; }
    
    String dump() {
        return super.dump() + " " + carrierLifetime + " " + intrinsicWidth;
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
    
    void getInfo(String arr[]) {
        arr[0] = "PIN diode";
        arr[1] = "I = " + getCurrentText(getCurrent());
        arr[2] = "Vd = " + getVoltageText(getVoltageDiff());
        arr[3] = "P = " + getUnitText(getPower(), "W");
        arr[4] = "Carrier lifetime = " + getUnitText(carrierLifetime, "s");
        arr[5] = "Intrinsic width = " + getUnitText(intrinsicWidth, "m");
        // Calculate and display RF resistance at current bias
        // Only show if reasonable value (filtering out very large resistances for clarity)
        double rfResistance = calculateRFResistance();
        if (rfResistance > 0 && rfResistance < 1e6)
            arr[6] = "RF resistance ≈ " + getUnitText(rfResistance, Locale.ohmString);
    }
    
    // Calculate RF resistance based on DC bias current
    // RF resistance is inversely proportional to DC current
    // At high frequencies, the stored charge in the intrinsic region
    // doesn't have time to be swept out, making it act like a resistor
    double calculateRFResistance() {
        double dcCurrent = Math.abs(getCurrent());
        if (dcCurrent < 1e-12)
            return 1e6; // very high resistance at near-zero current
        
        // Simplified model: R_RF ≈ (k*T/q) * (τ/(Q*I_DC))
        // where τ is carrier lifetime, Q is charge stored
        // For simplicity, we use: R_RF ≈ k / I_DC
        // where k is proportional to carrier lifetime and intrinsic region properties
        // The 1e6 scaling factor converts the resistance to a practical range in ohms
        // based on typical PIN diode parameters (microseconds and micrometers)
        double k = carrierLifetime * intrinsicWidth * 1e6; // scaling factor for practical ohm values
        return k / dcCurrent;
    }
    
    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return super.getEditInfo(0);
        if (n == 1)
            return new EditInfo("Carrier Lifetime (s)", carrierLifetime, 0, 0);
        if (n == 2)
            return new EditInfo("Intrinsic Width (m)", intrinsicWidth, 0, 0);
        // n >= 3: map to super's n >= 1 (buttons)
        return super.getEditInfo(n - 2);
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
        // n >= 3: map to super's n >= 1 (buttons)
        super.setEditValue(n - 2, ei);
    }
    
    int getShortcut() { return 0; }
    
    void setLastModelName(String n) {
        lastPINModelName = n;
    }
}
