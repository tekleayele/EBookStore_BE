package business.order;

import api.ApiException;
import business.BookstoreDbException;
import business.JdbcUtils;
import business.book.Book;
import business.book.BookDao;
import business.cart.ShoppingCart;
import business.cart.ShoppingCartItem;
import business.customer.Customer;
import business.customer.CustomerDao;
import business.customer.CustomerForm;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DefaultOrderService implements OrderService {

	private Logger logger = Logger.getLogger(DefaultOrderService.class.getName());

	private BookDao bookDao;

	private OrderDao orderDao;

	private CustomerDao customerDao;

	private LineItemDao lineItemDao;

	public void setOrderDao(OrderDao orderDao) { this.orderDao = orderDao; }
	public void setBookDao(BookDao bookDao) {
		this.bookDao = bookDao;
	}
	public void setCustomerDao(CustomerDao customerDao) {
		this.customerDao = customerDao;
	}
	public void setLineItemDao(LineItemDao lineItemDao) {
		this.lineItemDao = lineItemDao;
	}

	public static final Pattern VALID_EMAIL_ADDRESS_REGEX =
			Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", Pattern.CASE_INSENSITIVE);


	@Override
	public OrderDetails getOrderDetails(long orderId) {
		Order order = orderDao.findByOrderId(orderId);
		Customer customer = customerDao.findByCustomerId(order.getCustomerId());
		List<LineItem> lineItems = lineItemDao.findByOrderId(orderId);
		List<Book> books = lineItems
				.stream()
				.map(lineItem -> bookDao.findByBookId(lineItem.getBookId()))
				.collect(Collectors.toList());
		return new OrderDetails(order, customer, lineItems, books);
	}

	@Override
    public long placeOrder(CustomerForm customerForm, ShoppingCart cart) {

		validateCustomer(customerForm);
		validateCart(cart);

		try (Connection connection = JdbcUtils.getConnection()) {
			LocalDate date = getLocalDate(
					customerForm.getCcExpiryMonth(),
					customerForm.getCcExpiryYear());
			return performPlaceOrderTransaction(
					customerForm.getName(),
					customerForm.getAddress(),
					customerForm.getPhone(),
					customerForm.getEmail(),
					customerForm.getCcNumber(),
					date, cart, connection);
		} catch (SQLException e) {
			throw new BookstoreDbException("Error during close connection for customer order", e);
		}
	}

	private LocalDate getLocalDate(String monthString,
								   String yearString) {
		int month = Integer.parseInt(monthString);
		int year = Integer.parseInt(yearString);
		LocalDate init = LocalDate.of(year, month, 1);
		return init.withDayOfMonth(init.lengthOfMonth());
	}

	private long performPlaceOrderTransaction(
			String name, String address, String phone,
			String email, String ccNumber, LocalDate date,
			ShoppingCart cart, Connection connection) {
		try {
			connection.setAutoCommit(false);
			long customerId = customerDao.create(
					connection, name, address, phone, email,
					ccNumber, date);
			long customerOrderId = orderDao.create(
					connection,
					cart.getComputedSubtotal() + cart.getSurcharge(),
					generateConfirmationNumber(), customerId);
			for (ShoppingCartItem item : cart.getItems()) {
				lineItemDao.create(connection, customerOrderId, item.getBookId(),
						item.getQuantity());
			}
			connection.commit();
			return customerOrderId;
		} catch (Exception e) {
			try {
				connection.rollback();
			} catch (SQLException e1) {
				throw new BookstoreDbException("Failed to roll back transaction", e1);
			}
			return 0;
		}
	}

	private int generateConfirmationNumber() {
		Random random = new Random();
		return random.nextInt(999999999);
	}

	private void validateCustomer(CustomerForm customerForm) {

		String name = customerForm.getName();
		if (name == null || name.equals("") ||  name.length() < 4 ||  name.length() > 45) {
			throw new ApiException.InvalidParameter("Invalid name field");
		}

		String address = customerForm.getAddress();
		if (address == null || address.equals("") ||  address.length() < 4 ||  address.length() > 45) {
			throw new ApiException.InvalidParameter("Invalid address field");
		}

		String phone = customerForm.getPhone();
		if (phone == null || phone.equals("")) {
			throw new ApiException.InvalidParameter("Invalid phone field");
		}

		String digits = phone.replaceAll("\\D", "");
		if (digits.length() != 10) {
			throw new ApiException.InvalidParameter("Invalid phone field (digits)");
		}

		String email = customerForm.getEmail();
		if (email == null || email.equals("")) {
			throw new ApiException.InvalidParameter("Invalid email field");
		} else if(!validateEmail(email)) {
			throw new ApiException.InvalidParameter("Invalid email field");
		}

		String ccNumber = customerForm.getCcNumber();
		if (ccNumber == null || ccNumber.equals("")) {
			throw new ApiException.InvalidParameter("Invalid credit card field");
		}

		String ccNumberDigits = ccNumber.replaceAll("[\\-\\s]", "");
		if (ccNumberDigits.length() < 14 ||  ccNumberDigits.length() > 16) {
			throw new ApiException.InvalidParameter("Invalid credit card field (digits)");
		}

		if (expiryDateIsInvalid(customerForm.getCcExpiryMonth(), customerForm.getCcExpiryYear())) {
			throw new ApiException.InvalidParameter("Invalid expiry date");
		}
	}

	public static boolean validateEmail(String emailStr) {
		Matcher matcher = VALID_EMAIL_ADDRESS_REGEX .matcher(emailStr);
		return matcher.find();
	}

	private boolean expiryDateIsInvalid(String ccExpiryMonth, String ccExpiryYear) {
		try {
			if(ccExpiryMonth == null || ccExpiryMonth.equals("") || ccExpiryYear == null || ccExpiryYear.equals("") ) {
				return true;
			} else {
				if(LocalDate.now().getYear() < Integer.parseInt(ccExpiryYear)) {
					return false;
				} else if(LocalDate.now().getYear() == Integer.parseInt(ccExpiryYear) &&
						(LocalDate.now().getMonthValue() == Integer.parseInt(ccExpiryMonth)
								|| LocalDate.now().getMonthValue() > Integer.parseInt(ccExpiryMonth))) {
					return false;
				}
			}
			return true;
		} catch (NumberFormatException e) {
			logger.log(Level.WARNING, e, ()->"Problem attempting to parse month and year");
			throw new ApiException.InvalidParameter("Invalid expiry month or year");
		}
	}

	private void validateCart(ShoppingCart cart) {

		if (cart.getItems().size() <= 0) {
			throw new ApiException.InvalidParameter("Cart is empty.");
		}

		cart.getItems().forEach(item-> {
			if (item.getQuantity() < 0 || item.getQuantity() > 99) {
				throw new ApiException.InvalidParameter("Invalid quantity");
			}
			Book databaseBook = bookDao.findByBookId(item.getBookId());
			if (item.getBookForm().getPrice() != databaseBook.getPrice()) {
				throw new ApiException.InvalidParameter("Invalid price");
			}
			if (item.getBookForm().getCategoryId() != databaseBook.getCategoryId()) {
				throw new ApiException.InvalidParameter("Invalid category");
			}
		});
	}

}
