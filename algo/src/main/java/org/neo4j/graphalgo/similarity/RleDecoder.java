package org.neo4j.graphalgo.similarity;

public class RleDecoder {
    private RleReader item1Reader = new RleReader(new double[0]);
    private RleReader item2Reader = new RleReader(new double[0]);
    private double[] item1Vector = new double[0];
    private double[] item2Vector = new double[0];
    private int initialSize;

    public void reset(double[] item1, double[] item2, int initialSize) {
        this.initialSize = initialSize;
        item1Reader.reset(item1);
        item2Reader.reset(item2);
        item1Vector = new double[initialSize];
        item2Vector = new double[initialSize];
    }

    public double[] item1() {
        for (int i = 0; i < initialSize; i++) {
            item1Reader.next();
            item1Vector[i] = item1Reader.value();
        }
        return item1Vector;
    }

    public double[] item2() {
        for (int i = 0; i < initialSize; i++) {
            item2Reader.next();
            item2Vector[i] = item2Reader.value();
        }
        return item2Vector;
    }
}
