package business.category;

import java.util.List;

public interface CategoryDao {

    public List<Category> findAll();

    public List<Category> findTopThree(final int limit);

    public Category findByCategoryId(long categoryId);

    public Category findByName(String categoryName);
}
