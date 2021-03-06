package org.jlab.service.dc;

import cnuphys.snr.NoiseReductionParameters;
import cnuphys.snr.clas12.Clas12NoiseAnalysis;
import cnuphys.snr.clas12.Clas12NoiseResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jlab.io.base.DataBank;
import org.jlab.io.base.DataEvent;
import org.jlab.io.hipo.HipoDataSource;
import org.jlab.io.hipo.HipoDataSync;
import org.jlab.rec.dc.Constants;
import org.jlab.rec.dc.banks.HitReader;
import org.jlab.rec.dc.banks.RecoBankWriter;
import org.jlab.rec.dc.cluster.ClusterCleanerUtilities;
import org.jlab.rec.dc.cluster.ClusterFinder;
import org.jlab.rec.dc.cluster.ClusterFitter;
import org.jlab.rec.dc.cluster.FittedCluster;
import org.jlab.rec.dc.cross.Cross;
import org.jlab.rec.dc.cross.CrossList;
import org.jlab.rec.dc.cross.CrossListFinder;
import org.jlab.rec.dc.cross.CrossMaker;
import org.jlab.rec.dc.hit.FittedHit;
import org.jlab.rec.dc.hit.Hit;
import org.jlab.rec.dc.segment.Segment;
import org.jlab.rec.dc.segment.SegmentFinder;
import org.jlab.rec.dc.timetodistance.TableLoader;
import org.jlab.rec.dc.timetodistance.TimeToDistanceEstimator;
import org.jlab.rec.dc.track.Track;
import org.jlab.rec.dc.track.TrackCandListFinder;
import org.jlab.rec.dc.trajectory.DCSwimmer;
import org.jlab.rec.dc.trajectory.RoadFinder;
import org.jlab.rec.dc.trajectory.Road;
import org.jlab.utils.groups.IndexedTable;

public class DCHBEngine extends DCEngine {

    String FieldsConfig="";
    AtomicInteger Run = new AtomicInteger(0);
    //DCGeant4Factory dcDetector;
    //String clasDictionaryPath ;

    public DCHBEngine() {
        super("DCHB");
    }

    @Override
    public boolean init() {
        // Load cuts
        Constants.Load();
        super.initializeMagneticFields();
        super.setStartTimeOption();
        super.LoadTables();
        return true;
    }
    @Override
    public boolean processDataEvent(DataEvent event) {
            //setRunConditionsParameters( event) ;
        if(event.hasBank("RUN::config")==false ) {
                return true;
        }

        DataBank bank = event.getBank("RUN::config");
        long   timeStamp = bank.getLong("timestamp", 0);
        double triggerPhase =0;
        // Load the constants
        //-------------------
        int newRun = bank.getInt("run", 0);
        if(newRun==0)
        	return true;
        
        if(Run.get()==0 || (Run.get()!=0 && Run.get()!=newRun)) { 
            if(timeStamp==-1)
                return true;
            
            IndexedTable tabJ=super.getConstantsManager().getConstants(newRun, "/calibration/dc/time_jitter");
            double period = tabJ.getDoubleValue("period", 0,0,0);
            int    phase  = tabJ.getIntValue("phase", 0,0,0);
            int    cycles = tabJ.getIntValue("cycles", 0,0,0);
            
            if(cycles>0) triggerPhase=period*((timeStamp+phase)%cycles); 

            TableLoader.FillT0Tables(newRun, super.variationName);
            TableLoader.Fill(super.getConstantsManager().getConstants(newRun, "/calibration/dc/time_to_distance/time2dist")); 
            
            double shift =0;
            if(newRun>1890) {
                shift = -1.9;
            }
            DCSwimmer.setMagneticFieldsScales(bank.getFloat("solenoid", 0), bank.getFloat("torus", 0), shift);
            Run.set(newRun);
            if(event.hasBank("MC::Particle")==true)
                Constants.setMCDIST(0);
        }
        // init SNR
       Clas12NoiseResult results = new Clas12NoiseResult();
       Clas12NoiseAnalysis noiseAnalysis = new Clas12NoiseAnalysis();

       int[] rightShifts = Constants.SNR_RIGHTSHIFTS;
       int[] leftShifts  = Constants.SNR_LEFTSHIFTS;
       NoiseReductionParameters parameters = new NoiseReductionParameters (
                       2,leftShifts,
                       rightShifts);
       
       //System.out.println("RUNNING HITBASED_________________________________________");

       ClusterFitter cf = new ClusterFitter();
       ClusterCleanerUtilities ct = new ClusterCleanerUtilities();

       List<FittedHit> fhits = new ArrayList<FittedHit>();
       List<FittedCluster> clusters = new ArrayList<FittedCluster>();
       List<Segment> segments = new ArrayList<Segment>();
       List<Cross> crosses = new ArrayList<Cross>();

       List<Track> trkcands = new ArrayList<Track>();

       //instantiate bank writer
       RecoBankWriter rbc = new RecoBankWriter();

       //if(Constants.DEBUGCROSSES)
       //	event.appendBank(rbc.fillR3CrossfromMCTrack(event));

       HitReader hitRead = new HitReader();
       hitRead.fetch_DCHits(event, noiseAnalysis, parameters, results, 
               super.getConstantsManager().getConstants(newRun, "/calibration/dc/time_to_distance/time2dist"), 
               super.getConstantsManager().getConstants(newRun,"/calibration/dc/time_corrections/tdctimingcuts"), 
               super.getConstantsManager().getConstants(newRun,"/calibration/dc/tracking/wire_status"), 
               dcDetector, triggerPhase);

       List<Hit> hits = new ArrayList<Hit>();
       //I) get the hits
       hits = hitRead.get_DCHits();

       //II) process the hits
       //1) exit if hit list is empty
       if(hits.isEmpty() ) {
               return true;
       }

       //
       //2) find the clusters from these hits
       ClusterFinder clusFinder = new ClusterFinder();
       clusters = clusFinder.FindHitBasedClusters(hits, ct, cf, dcDetector);

       if(clusters.isEmpty()) {				
            //rbc.fillAllHBBanks(event, rbc, hits, null, null, null, null);
            return true;
       }
       fhits = rbc.createRawHitList(hits);
       rbc.updateListsListWithClusterInfo(fhits, clusters);

       //3) find the segments from the fitted clusters
       SegmentFinder segFinder = new SegmentFinder();
       segments =  segFinder.get_Segments(clusters, event, dcDetector);

       if(segments.isEmpty()) { // need 6 segments to make a trajectory			
            rbc.fillAllHBBanks(event, rbc, fhits, clusters, null, null, null);
            return true;
       }
       List<Segment> rmSegs = new ArrayList<Segment>();
       // clean up hit-based segments
       for(Segment se : segments) {
           double trkDocOverCellSize =0;

           for(FittedHit fh : se.get_fittedCluster()) {
               trkDocOverCellSize+=fh.get_ClusFitDoca()/fh.get_CellSize();
           }

           if(trkDocOverCellSize/se.size()>1.1) {
               rmSegs.add(se);
            }
        }
        segments.removeAll(rmSegs);

        CrossMaker crossMake = new CrossMaker();
        crosses = crossMake.find_Crosses(segments, dcDetector);

        if(crosses.isEmpty() ) {			
                rbc.fillAllHBBanks(event, rbc, fhits, clusters, segments, null, null);
                return true;
        }


        CrossListFinder crossLister = new CrossListFinder();
        CrossList crosslist = crossLister.candCrossLists(crosses, false, super.getConstantsManager().getConstants(newRun, "/calibration/dc/time_to_distance/time2dist"), dcDetector, null);

        //6) find the list of  track candidates
        TrackCandListFinder trkcandFinder = new TrackCandListFinder("HitBased");
        trkcands = trkcandFinder.getTrackCands(crosslist, dcDetector, DCSwimmer.getTorScale() ) ;


        // track found	
        int trkId = 1;
                
        if(trkcands.size()>0) {
            trkcandFinder.removeOverlappingTracks(trkcands);		// remove overlaps

            for(Track trk: trkcands) {
                // reset the id
                trk.set_Id(trkId);
                trkcandFinder.matchHits(trk.get_Trajectory(), trk, dcDetector);
                for(Cross c : trk) { 
                    c.get_Segment1().isOnTrack=true;
                    c.get_Segment2().isOnTrack=true;

                    for(FittedHit h1 : c.get_Segment1()) { 
                            h1.set_AssociatedHBTrackID(trk.get_Id());

                    }
                    for(FittedHit h2 : c.get_Segment2()) {
                            h2.set_AssociatedHBTrackID(trk.get_Id());                              
                    }
                }
                trkId++;
            }
        }    
        
        List<Segment> crossSegsNotOnTrack = new ArrayList<Segment>();
        List<Segment> psegments = new ArrayList<Segment>();
        for(Cross c : crosses) { 
            if(c.get_Segment1().isOnTrack==false)
                crossSegsNotOnTrack.add(c.get_Segment1());
            if(c.get_Segment2().isOnTrack==false)
                crossSegsNotOnTrack.add(c.get_Segment2());
        }
        
        RoadFinder rf = new RoadFinder();
        List<Road> allRoads =rf.findRoads(segments, dcDetector);
        
        for(Road r : allRoads) {
            List<Segment> Segs2Road = new ArrayList<Segment>(); 
            int missingSL = -1;
            for(int ri = 0; ri<3; ri++) { 
                if(r.get(ri).associatedCrossId==-1) {
                    if(r.get(ri).get_Superlayer()%2==1) {
                        missingSL = r.get(ri).get_Superlayer()+1;
                    } else {
                        missingSL = r.get(ri).get_Superlayer()-1;
                    }
                }
            }
            for(int ri = 0; ri<3; ri++) { 
                for(Segment s : crossSegsNotOnTrack) { 
                    if(s.get_Sector()==r.get(ri).get_Sector() && s.get_Region()==r.get(ri).get_Region() 
                            && s.associatedCrossId==r.get(ri).associatedCrossId && r.get(ri).associatedCrossId!=-1) {
                        if(s.get_Superlayer()%2==missingSL%2)
                            Segs2Road.add(s);
                    }
                }
            }

            if(Segs2Road.size()==2) {
                Segment pSegment = rf.findRoadMissingSegment(Segs2Road, dcDetector, r.a) ;
                if(pSegment!=null)
                    psegments.add(pSegment);
            }
        }
        
        
        segments.addAll(psegments);

        List<Cross> pcrosses = crossMake.find_Crosses(segments, dcDetector);

        //
        CrossList pcrosslist = crossLister.candCrossLists(pcrosses, false, super.getConstantsManager().getConstants(newRun, "/calibration/dc/time_to_distance/time2dist"), dcDetector, null);

        List<Track> mistrkcands =trkcandFinder.getTrackCands(pcrosslist, dcDetector, DCSwimmer.getTorScale());
        if(mistrkcands.size()>0) {    
            trkcandFinder.removeOverlappingTracks(mistrkcands);		// remove overlaps

            for(Track trk: mistrkcands) {

                // reset the id
                trk.set_Id(trkId);
                trkcandFinder.matchHits(trk.get_Trajectory(), trk, dcDetector);
                for(Cross c : trk) { 
                    for(FittedHit h1 : c.get_Segment1()) { 
                            h1.set_AssociatedHBTrackID(trk.get_Id());
                    }
                    for(FittedHit h2 : c.get_Segment2()) {
                            h2.set_AssociatedHBTrackID(trk.get_Id());                              
                    }
                }
                trkId++;
            }
        }
        trkcands.addAll(mistrkcands) ;

        if(trkcands.isEmpty()) {

                rbc.fillAllHBBanks(event, rbc, fhits, clusters, segments, crosses, null); // no cand found, stop here and save the hits, the clusters, the segments, the crosses
                return true;
        }
        rbc.fillAllHBBanks(event, rbc, fhits, clusters, segments, crosses, trkcands);
        return true;
    }

    public static void main(String[] args)  {
        
        //String inputFile = args[0];
        //String outputFile = args[1];
        //String inputFile="/Users/ziegler/Desktop/Work/Files/Data/DecodedData/clas_003305.hipo";
        //String inputFile="/Users/ziegler/Desktop/Work/validation/infiles/out_clas_004013.evio.filt.hipo";
        //String inputFile="/Users/ziegler/Desktop/Work/Files/GEMC/BGMERG/gemc_out_mu-_hipo/mu-_30nA_bg_out.ev.hipo";
         String inputFile="/Users/ziegler/Desktop/Work/Files/Data/out_clas_002391_extractRawBanksFromCooked.hipo";
        //String inputFile="/Users/ziegler/Desktop/Work/Files/FMTDevel/gemc/pion_rec.hipo";
        //System.err.println(" \n[PROCESSING FILE] : " + inputFile);
        
        DCHBEngine en = new DCHBEngine();
        en.init();
        
        DCTBEngine en2 = new DCTBEngine();
        en2.init();
             
        int counter = 0;
        
        HipoDataSource reader = new HipoDataSource();
        reader.open(inputFile);
        
        HipoDataSync writer = new HipoDataSync();
        //Writer
        
        //String outputFile="/Users/ziegler/Desktop/Work/Files/Data/DecodedData/clas_003305_recGDSt.hipo";
       // String outputFile="/Users/ziegler/Desktop/Work/validation/outfiles/out_clas_004013.evio.filtRecookSinThread.hipo";
        String outputFile="/Users/ziegler/Desktop/Work/Files/Data/out_clas_002391_extractRawBanksFromCookedReCook.hipo";
        //String outputFile="/Users/ziegler/Desktop/Work/Files/FMTDevel/gemc/pion_recFMTClusNoTrkRefit.hipo";
        
        writer.open(outputFile);
        TimeToDistanceEstimator tde = new TimeToDistanceEstimator();
        long t1 = 0;
        while (reader.hasEvent()) {
            
            counter++;
            System.out.println("************************************************************* ");
            DataEvent event = reader.getNextEvent();
            if (counter > 0) {
                t1 = System.currentTimeMillis();
            }
            //if(event.getBank("RUN::config").getInt("event", 0) <50)
             //   continue;
            en.processDataEvent(event);
            //event.show();
            // Processing TB
            en2.processDataEvent(event);
            writer.writeEvent(event);
            System.out.println("PROCESSED  EVENT "+event.getBank("RUN::config").getInt("event", 0));
           // event.show();
            //if (event.getBank("RUN::config").getInt("event", 0) > 350) {
            //    break;
            //}
            
            
            // event.show();
            //if(counter%100==0)
            
            //if(event.hasBank("HitBasedTrkg::HBTracks")) {
            //    event.show();
            
            //}
        }
        writer.close();
        double t = System.currentTimeMillis() - t1;
        System.out.println(t1 + " TOTAL  PROCESSING TIME = " + (t / (float) counter));
    }

}
