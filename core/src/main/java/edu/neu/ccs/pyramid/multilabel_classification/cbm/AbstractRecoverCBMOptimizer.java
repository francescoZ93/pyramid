package edu.neu.ccs.pyramid.multilabel_classification.cbm;

import edu.neu.ccs.pyramid.classification.PriorProbClassifier;
import edu.neu.ccs.pyramid.clustering.bm.BMSelector;
import edu.neu.ccs.pyramid.dataset.*;
import edu.neu.ccs.pyramid.util.ArgMax;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.mahout.math.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Created by chengli on 4/9/17.
 */
public abstract class AbstractRecoverCBMOptimizer {
    private static final Logger logger = LogManager.getLogger();
    protected CBM cbm;
    protected MultiLabelClfDataSet dataSet;

    protected MultiLabelClfDataSet groundTruth;

    // format [#data][#components]
    protected double[][] gammas;


    // if the fraction of positive labels < threshold, or > 1-threshold,  skip the binary model, use prior probability
    // set threshold = 0 if we don't want to skip any
    protected double skipLabelThreshold = 1E-5;

    // if gamma_i^k is smaller than this threshold, skip it when training binary classifiers in component k
    // set threshold = 0 if we don't want to skip any
    protected double skipDataThreshold = 1E-5;


    protected int multiclassUpdatesPerIter = 10;
    protected int binaryUpdatesPerIter = 10;

    protected double smoothingStrength =0.0001;


    protected DataSet labelMatrix;

//    private double dropProb = 0.01;

    private double lambda=0;
    private int flipped=0;

    public AbstractRecoverCBMOptimizer(CBM cbm, MultiLabelClfDataSet dataSet) {
        this.cbm = cbm;
        this.dataSet = dataSet;

        this.gammas = new double[dataSet.getNumDataPoints()][cbm.getNumComponents()];
        double average = 1.0/ cbm.getNumComponents();
        for (int n=0;n<dataSet.getNumDataPoints();n++){
            for (int k = 0; k< cbm.getNumComponents(); k++){
                gammas[n][k] = average;
            }
        }

        this.labelMatrix = DataSetBuilder.getBuilder()
                .numDataPoints(dataSet.getNumDataPoints())
                .numFeatures(dataSet.getNumClasses())
                .density(Density.SPARSE_RANDOM)
                .build();
        for (int i=0;i<dataSet.getNumDataPoints();i++){
            MultiLabel multiLabel = dataSet.getMultiLabels()[i];
            for (int l: multiLabel.getMatchedLabels()){
                labelMatrix.setFeatureValue(i,l,1);
            }
        }



        List<Integer> all = IntStream.range(0, dataSet.getNumDataPoints()).boxed().collect(Collectors.toList());
        groundTruth = DataSetUtil.sampleData(dataSet, all);
    }

//    public void setDropProb(double dropProb) {
//        this.dropProb = dropProb;
//    }


    public void setLambda(double lambda) {
        this.lambda = lambda;
    }


    public void setSmoothingStrength(double smoothingStrength) {
        this.smoothingStrength = smoothingStrength;
    }

    public void setMulticlassUpdatesPerIter(int multiclassUpdatesPerIter) {
        this.multiclassUpdatesPerIter = multiclassUpdatesPerIter;
    }

    public void setBinaryUpdatesPerIter(int binaryUpdatesPerIter) {
        this.binaryUpdatesPerIter = binaryUpdatesPerIter;
    }

    public void setSkipLabelThreshold(double skipLabelThreshold) {
        this.skipLabelThreshold = skipLabelThreshold;
    }

    public void setSkipDataThreshold(double skipDataThreshold) {
        this.skipDataThreshold = skipDataThreshold;
    }

    public void initialize(){
        if (cbm.getNumComponents()>1){
            gammas = BMSelector.selectGammas(dataSet.getNumClasses(),dataSet.getMultiLabels(), cbm.getNumComponents());
            if (logger.isDebugEnabled()){
                logger.debug("performing M step");
            }
        }

        mStep();
    }

    public void iterate() {
        eStep();
        mStep();
    }


    public void updateGroundTruth(){
        List<Change> ranked = rank();
        for (Change change: ranked){
            int data = change.data;
            int label = change.label;
            double estimatedChange = change.totalChange;
            Change realChange = lossChange(data, label);
            System.out.println("for data "+data+" class "+label+" estimated totalChange = "+estimatedChange+", real totalChange = "+realChange.totalChange+", change in NLL="+realChange.changeInNll+", change in penalty = "+realChange.changeInPenalty);
            if (realChange.totalChange<0){
                // flip or reset flip
                groundTruth.getMultiLabels()[data].flipLabel(label);
                int newGroundTruth = 0;
                if (groundTruth.getMultiLabels()[data].matchClass(label)){
                    newGroundTruth = 1;
                }
                labelMatrix.setFeatureValue(data, label, newGroundTruth);
                if (newGroundTruth==1){
                    flipped += 1;
                    System.out.println("flip label "+label+" for data "+data+" from 0 to 1. #flips = "+flipped);
                } else {
                    flipped -= 1;
                    System.out.println("reset label "+label+" for data "+data+" from 1 to 0. #flips = "+flipped);
                }
            } else {
                System.out.println("break");
                break;
            }
        }
    }

//    protected void updateGroundTruth(int dataPoint){
//        for (int l=0;l<dataSet.getNumClasses();l++){
//            if (!dataSet.getMultiLabels()[dataPoint].matchClass(l)){
//                updateGroundTruth(dataPoint, l);
//            }
//
//        }
//    }

    private List<Change> rank(){

        List<Change> all = IntStream.range(0,dataSet.getNumDataPoints()).parallel().boxed()
                .flatMap(i->IntStream.range(0, dataSet.getNumClasses())
                        .filter(l->!dataSet.getMultiLabels()[i].matchClass(l))
                        .mapToObj(l->lossChange(i,l)))
                .collect(Collectors.toList());


//        for (int i=0;i<dataSet.getNumDataPoints();i++){
//            for (int l=0;l<dataSet.getNumClasses();l++){
//                if (!dataSet.getMultiLabels()[i].matchClass(l)){
//                    Change change = lossChange(i,l);
//                    all.add(change);
//                }
//
//            }
//        }
        List<Change> sorted = all.stream().sorted(Comparator.comparing(change->change.totalChange)).collect(Collectors.toList());
        return sorted;
    }

    // assuming the tau is in the extremes
    protected Change lossChange(int dataPoint, int label){

        double[][] logProbs = new double[cbm.getNumComponents()][2];
        for (int k=0;k<cbm.getNumComponents();k++){
            logProbs[k] = cbm.getBinaryClassifiers()[k][label].predictLogClassProbs(dataSet.getRow(dataPoint));
        }
        //assuming the given label =0

        int currentGroundTruth = 0;
        if (groundTruth.getMultiLabels()[dataPoint].matchClass(label)){
            currentGroundTruth = 1;
        }

        double currentNll = 0;
        for (int k=0;k<cbm.getNumComponents();k++){
            currentNll += -logProbs[k][currentGroundTruth]*gammas[dataPoint][k];
        }


//        double currentPenalty =  lambda*Math.abs(flipped-tau*dataSet.getNumDataPoints());



        int newGroundTruth = 1-currentGroundTruth;
        double newNLL =0;
        for (int k=0;k<cbm.getNumComponents();k++){
            newNLL += -logProbs[k][newGroundTruth]*gammas[dataPoint][k];
        }


//        double newPenalty;


        Change change = new Change();
        change.changeInNll = newNLL - currentNll;
        if (newGroundTruth==1){
//            newPenalty = lambda*Math.abs(flipped+1-tau*dataSet.getNumDataPoints());
            change.changeInPenalty = lambda;
        } else {
//            newPenalty = lambda*Math.abs(flipped-1-tau*dataSet.getNumDataPoints());
            change.changeInPenalty = -lambda;
        }

        change.totalChange = newNLL - currentNll + change.changeInPenalty;
        change.data = dataPoint;
        change.label = label;
        return change;
    }



//    protected void updateGroundTruth(int dataPoint, int label){
//
//        double[][] logProbs = new double[cbm.getNumComponents()][2];
//        for (int k=0;k<cbm.getNumComponents();k++){
//            logProbs[k] = cbm.getBinaryClassifiers()[k][label].predictLogClassProbs(dataSet.getRow(dataPoint));
//        }
//        //assuming the given label =0
//
//        int currentGroundTruth = 0;
//        if (groundTruth.getMultiLabels()[dataPoint].matchClass(label)){
//            currentGroundTruth = 1;
//        }
//
//        double currentObj = 0;
//        for (int k=0;k<cbm.getNumComponents();k++){
//            currentObj += -logProbs[k][currentGroundTruth]*gammas[dataPoint][k];
//        }
//
//
//
//        if (currentGroundTruth==1){
//            currentObj += -Math.log(dropProb);
//
//        }
//
//
//        int newGroundTruth = 1-currentGroundTruth;
//        double newObj =0;
//        for (int k=0;k<cbm.getNumComponents();k++){
//            newObj += -logProbs[k][newGroundTruth]*gammas[dataPoint][k];
//        }
//
//
//
//        if (newGroundTruth==1){
//            newObj += -Math.log(dropProb);
//
//        }
//
//
//        if (newObj < currentObj){
//            System.out.println("flipping ground truth label for class "+label+"("+dataSet.getLabelTranslator().toExtLabel(label)+") for data point "
//                    +dataPoint+"("+dataSet.getIdTranslator().toExtId(dataPoint)+") from "+currentGroundTruth+" to "+newGroundTruth);
//
//            groundTruth.getMultiLabels()[dataPoint].flipLabel(label);
//            labelMatrix.setFeatureValue(dataPoint, label, newGroundTruth);
//        }
//    }

    protected void eStep(){
        if (logger.isDebugEnabled()){
            logger.debug("start E step");
        }
        updateGamma();
        if (logger.isDebugEnabled()){
            logger.debug("finish E step");
//            logger.debug("objective = "+getObjective());
        }
    }


    protected void updateGamma() {
        IntStream.range(0, groundTruth.getNumDataPoints()).parallel()
                .forEach(this::updateGamma);
    }

    protected void updateGamma(int n) {
        Vector x = groundTruth.getRow(n);
        MultiLabel y = groundTruth.getMultiLabels()[n];
        double[] posterior = cbm.posteriorMembershipShortCircuit(x, y);
        for (int k=0; k<cbm.numComponents; k++) {
            gammas[n][k] = posterior[k];
        }
    }

    void mStep() {
        if (logger.isDebugEnabled()){
            logger.debug("start M step");
        }
        updateBinaryClassifiers();
        updateMultiClassClassifier();
        if (logger.isDebugEnabled()){
            logger.debug("finish M step");
//            logger.debug("objective = "+getObjective());
        }
    }

    protected void updateBinaryClassifiers() {
        if (logger.isDebugEnabled()){
            logger.debug("start updateBinaryClassifiers");
        }
        IntStream.range(0, cbm.numComponents).forEach(this::updateBinaryClassifiers);
        if (logger.isDebugEnabled()){
            logger.debug("finish updateBinaryClassifiers");
        }
    }

    //todo pay attention to parallelism
    protected void updateBinaryClassifiers(int component){

        if (logger.isDebugEnabled()){
            logger.debug("computing active dataset for component " +component);
        }

        // skip small gammas
        List<Double> activeGammasList = new ArrayList<>();
        List<Integer> activeIndices = new ArrayList<>();
        double[] gammasForComponent = IntStream.range(0, groundTruth.getNumDataPoints()).mapToDouble(i->gammas[i][component]).toArray();
        int maxIndex = ArgMax.argMax(gammasForComponent);

        double weightedTotal = 0;
        double thresholdedWeightedTotal = 0;
        int counter = 0;
        for (int i=0;i<groundTruth.getNumDataPoints();i++){
            double v = gammas[i][component];
            weightedTotal += v;
            if (v>= skipDataThreshold || i==maxIndex){
                activeGammasList.add(v);
                activeIndices.add(i);
                thresholdedWeightedTotal += v;
                counter += 1;
            }
        }

        //todo deal with empty components



        double[] activeGammas = activeGammasList.stream().mapToDouble(a->a).toArray();

        if (logger.isDebugEnabled()){
            logger.debug("number of active data  = "+ counter);
            logger.debug("total weight  = "+weightedTotal);
            logger.debug("total weight of active data  = "+thresholdedWeightedTotal);
            logger.debug("creating active dataset");
        }


        MultiLabelClfDataSet activeDataSet = DataSetUtil.sampleData(groundTruth, activeIndices);
        int activeFeatures = (int) IntStream.range(0, activeDataSet.getNumFeatures()).filter(j->activeDataSet.getColumn(j).getNumNonZeroElements()>0).count();
        if (logger.isDebugEnabled()){
            logger.debug("active dataset created");
            logger.debug("number of active features = "+activeFeatures);
        }

        // to please lambda
        final double totalWeight = weightedTotal;
        IntStream.range(0, cbm.numLabels).parallel()
                .forEach(l-> skipOrUpdateBinaryClassifier(component,l, activeDataSet, activeGammas, totalWeight));
    }


    protected void skipOrUpdateBinaryClassifier(int component, int label, MultiLabelClfDataSet activeDataSet,
                                                double[] activeGammas, double totalWeight){
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        double effectivePositives = effectivePositives(component, label);

        double nonSmoothedPositiveProb = effectivePositives/totalWeight;

        // smooth the component-wise label fraction with global label fraction
        double positiveCount = labelMatrix.getColumn(label).getNumNonZeroElements();

        double smoothedPositiveProb = (effectivePositives+smoothingStrength*positiveCount)/(totalWeight+smoothingStrength*groundTruth.getNumDataPoints());

        StringBuilder sb = new StringBuilder();
        sb.append("for component ").append(component).append(", label ").append(label);
        sb.append(", weighted positives = ").append(effectivePositives);
        sb.append(", non-smoothed positive fraction = "+(effectivePositives/totalWeight));
        sb.append(", global positive fraction = "+(positiveCount/groundTruth.getNumDataPoints()));
        sb.append(", smoothed positive fraction = "+smoothedPositiveProb);


//        if (positiveProb==0){
//            positiveProb=1.0E-50;
//        }

        // it be happen that p >1 for numerical reasons
        if (smoothedPositiveProb>=1){
            smoothedPositiveProb=1;
        }

        if (nonSmoothedPositiveProb<skipLabelThreshold || nonSmoothedPositiveProb>1-skipLabelThreshold){
            double[] probs = {1-smoothedPositiveProb, smoothedPositiveProb};
            cbm.binaryClassifiers[component][label] = new PriorProbClassifier(probs);
            sb.append(", skip, use prior = ").append(smoothedPositiveProb);
            sb.append(", time spent = ").append(stopWatch.toString());
            if (logger.isDebugEnabled()){
                logger.debug(sb.toString());
            }
            return;
        }

        if (logger.isDebugEnabled()){
            logger.debug(sb.toString());
        }
        updateBinaryClassifier(component, label, activeDataSet, activeGammas);
    }

    abstract protected void updateBinaryClassifier(int component, int label, MultiLabelClfDataSet activeDataset, double[] activeGammas);

    protected abstract void updateMultiClassClassifier();

    private double effectivePositives(int componentIndex, int labelIndex){
        double sum = 0;
        Vector labelColumn = labelMatrix.getColumn(labelIndex);
        for (Vector.Element element: labelColumn.nonZeroes()){
            int dataIndex = element.index();
            sum += gammas[dataIndex][componentIndex];
        }
        return sum;
    }



    //******************** for debugging *****************************

    public double getObjective() {
        return multiClassClassifierObj() + binaryObj();
    }

    protected double binaryObj(){
        return IntStream.range(0, cbm.numComponents).mapToDouble(this::binaryObj).sum();
    }

    protected double binaryObj(int component){
        return IntStream.range(0, cbm.numLabels).parallel().mapToDouble(l->binaryObj(component,l)).sum();
    }

    protected abstract double binaryObj(int component, int classIndex);

    protected abstract double multiClassClassifierObj();

    public double[][] getGammas() {
        return gammas;
    }

    public MultiLabelClfDataSet getGroundTruth() { return groundTruth; }

    private void checkGamma(){
        for (int i=0;i<gammas.length;i++){
            for (int k=0;k<gammas[0].length;k++){
                if (Double.isNaN(gammas[i][k])){
                    throw new RuntimeException("gamma "+i+" "+k+" is NaN");
                }
            }
        }
    }


    private static class Change{
        int data;
        int label;
        double totalChange;
        double changeInNll;
        double changeInPenalty;
    }
}
