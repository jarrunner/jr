package t;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
public @interface Acquires { String value(); }
