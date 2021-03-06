/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jlab.detector.geant4.v2;

import eu.mihosoft.vrl.v3d.Vector3d;
import java.io.InputStream;
import static org.jlab.detector.hits.DetId.CTOFID;
import org.jlab.detector.units.SystemOfUnits.Length;
import org.jlab.detector.volume.G4Stl;
import org.jlab.detector.volume.G4World;
import org.jlab.detector.volume.Geant4Basic;
import org.jlab.geometry.prim.Line3d;

/**
 *
 * @author kenjo
 */
public final class CTOFGeant4Factory extends Geant4Factory {

    private final int npaddles = 48;

    public CTOFGeant4Factory() {
        motherVolume = new G4World("fc");

        ClassLoader cloader = getClass().getClassLoader();

        for (String name : new String[]{"sc", "lgd"}) {
            for (int iscint = 1; iscint <= npaddles; iscint++) {
                CTOFpaddle component = new CTOFpaddle(String.format("%s%02d", name, iscint),
                        cloader.getResourceAsStream(String.format("ctof/cad/%s%02d.stl", name, iscint)), iscint);
                component.scale(Length.mm / Length.cm);

                component.rotate("zyx", Math.toRadians(97.5), Math.toRadians(180), 0);
                component.translate(0, 0, 127.327);
                component.setMother(motherVolume);

                if (name.equals("sc")) {
                    component.makeSensitive();
                    component.setId(CTOFID, iscint);
                }
            }
        }
    }

    public Geant4Basic getPaddle(int ipaddle) {
        if (ipaddle < 1 || ipaddle > npaddles) {
            System.err.println("ERROR!!!");
            System.err.println("CTOF Paddle #" + ipaddle + " doesn't exist");
            System.exit(111);
        }
        return motherVolume.getChildren().get(ipaddle - 1);
    }

    private class CTOFpaddle extends G4Stl {

        private Line3d centerline;
        private final double angle0 = 3.75, dangle = 7.5;
        private final double radius = 25.11;
        private final double thickness = 3.0266;
        private final double lengthOdd = 88.9328;
        private final double offsetOdd = -8.9874;
        private final double lengthEven = 88.0467;
        private final double offsetEven = -8.5031;
        
//        private final double zmin = -54.18, zmax = 36.26;

        CTOFpaddle(String name, InputStream stlstream, int padnum) {
            super(name, stlstream);
            Vector3d cent = new Vector3d(radius+thickness/2.,0, 0);
            cent.rotateZ(Math.toRadians(angle0 + (padnum - 1) * dangle));
            if(padnum%2==0) centerline = new Line3d(new Vector3d(cent.x, cent.y, -lengthEven/2+offsetEven), new Vector3d(cent.x, cent.y, lengthEven/2+offsetEven));
            else            centerline = new Line3d(new Vector3d(cent.x, cent.y, -lengthOdd/2+offsetOdd), new Vector3d(cent.x, cent.y, lengthOdd/2+offsetOdd));
        }

        @Override
        public Line3d getLineZ() {
            return centerline;
        }
    }

    public static void main(String[] args) {
        CTOFGeant4Factory factory = new CTOFGeant4Factory();

        for (int ipad = 15; ipad <= 19; ipad++) {
            Geant4Basic pad = factory.getPaddle(ipad);
            Line3d line = pad.getLineZ();
            System.out.println(line);
        }
    }
}
