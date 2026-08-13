package com.example.chat.repository;

import com.example.chat.entity.OnlineCountRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface OnlineCountRepository {

    @Insert("INSERT INTO online_count_records (page, count, recorded_at) VALUES (#{page}, #{count}, #{recordedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OnlineCountRecord record);

    @Select("SELECT id, page, count, recorded_at AS recordedAt FROM online_count_records WHERE recorded_at >= #{since} ORDER BY recorded_at ASC, page ASC")
    @Results({
        @Result(property = "id", column = "id"),
        @Result(property = "page", column = "page"),
        @Result(property = "count", column = "count"),
        @Result(property = "recordedAt", column = "recorded_at")
    })
    List<OnlineCountRecord> findRecent(String since);

    @Select("SELECT DISTINCT page FROM online_count_records ORDER BY page")
    List<String> findDistinctPages();

    @Select("SELECT COALESCE(SUM(count), 0) FROM online_count_records")
    long sumAllCounts();
}
