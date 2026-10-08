package t;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
public @interface Fails { String value(); }
