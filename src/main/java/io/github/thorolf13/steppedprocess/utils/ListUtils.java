package io.github.thorolf13.steppedprocess.utils;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class ListUtils {
    public static  <T> List<T> concat(List<T> ...lists){
        List<T> result = new ArrayList<>();
        for (List<T> list : lists) {
            if( !CollectionUtils.isEmpty(list)){
                result.addAll(list);
            }
        }
        return result;
    }
}
