package tacos.web.api;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

  private List<T> content;
  private int page;
  private int size;
  private long totalElements;
  private int totalPages;
  private boolean first;
  private boolean last;

  public PageResponse(List<T> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {
    this(content, page, size, totalElements, totalPages, page == 0, !hasNext);
  }

  public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
    int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
    boolean first = page == 0;
    boolean last = page >= totalPages - 1;
    return new PageResponse<>(content, page, size, totalElements, totalPages, first, last);
  }
}
