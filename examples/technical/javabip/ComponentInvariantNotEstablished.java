package org.javabip.spec.deviation;

import org.javabip.annotations.*;
import org.javabip.api.PortType;

import java.time.LocalDateTime;
import java.util.List;

@ComponentType(initial = INIT, name = NAME)
@Invariant(expr = "x > 0")
public class ComponentInvariantNotEstablished {
    public static final String INIT = "initialState";
    public static final String NAME = "oneComponentOneTransition";

    OneComponentOneTransition() { }

    private int x;
}

