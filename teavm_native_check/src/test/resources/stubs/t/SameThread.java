package t;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
public @interface SameThread { String value(); }
