package de.tuberlin.aura.core.processing.udfs.functions;

import de.tuberlin.aura.core.processing.udfs.contracts.IMapFunction;

/**
 *
 */
public abstract class MapFunction<I,O> extends AbstractFunction implements IMapFunction<I,O> {

    // ---------------------------------------------------
    // Public Methods.
    // ---------------------------------------------------

    public abstract O map(final I in);
}