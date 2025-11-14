package com.example.libreria.service;

import com.example.libreria.dto.ReservationRequestDTO;
import com.example.libreria.dto.ReservationResponseDTO;
import com.example.libreria.dto.ReturnBookRequestDTO;
import com.example.libreria.model.Book;
import com.example.libreria.model.Reservation;
import com.example.libreria.model.User;
import com.example.libreria.repository.BookRepository;
import com.example.libreria.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {
    
    private static final BigDecimal LATE_FEE_PERCENTAGE = new BigDecimal("0.15"); // 15% por día
    
    private final ReservationRepository reservationRepository;
    private final BookRepository bookRepository;
    private final BookService bookService;
    private final UserService userService;

    @Transactional
    public ReservationResponseDTO createReservation(ReservationRequestDTO requestDTO) {
        User u = userService.getUserEntity(requestDTO.getUserId());

        Book b = bookRepository.findByExternalId(requestDTO.getBookExternalId())
                .orElseThrow(() -> new RuntimeException("Libro no encontrado con el ID que pusiste: " + requestDTO.getBookExternalId()));

        if (b.getAvailableQuantity() == null || b.getAvailableQuantity() <= 0) {
            throw new RuntimeException("No hay libros para reservar ahora");
        }

        Reservation r = new Reservation();
        r.setUser(u);
        r.setBook(b);
        r.setRentalDays(requestDTO.getRentalDays());
        r.setStartDate(requestDTO.getStartDate());

        LocalDate expectedReturnDate = requestDTO.getStartDate().plusDays(requestDTO.getRentalDays());
        r.setExpectedReturnDate(expectedReturnDate);

        r.setDailyRate(b.getPrice());
        r.setTotalFee(calculateTotalFee(b.getPrice(), requestDTO.getRentalDays()));
        r.setLateFee(BigDecimal.ZERO);
        r.setStatus(Reservation.ReservationStatus.ACTIVE);

        Reservation guardada = reservationRepository.save(r);

        bookService.decreaseAvailableQuantity(b.getExternalId());

        return convertToDTO(guardada);
    }

    @Transactional
    public ReservationResponseDTO returnBook(Long reservationId, ReturnBookRequestDTO returnRequest) {
        Reservation r = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new RuntimeException("Reserva no encontrada con ese ID: " + reservationId));

        if (r.getStatus() != Reservation.ReservationStatus.ACTIVE) {
            throw new RuntimeException("La reserva ya fue entregada/devuelta");
        }

        LocalDate returnDate = returnRequest.getReturnDate();
        r.setActualReturnDate(returnDate);

        LocalDate expectedReturnDate = r.getExpectedReturnDate();

        if (returnDate.isAfter(expectedReturnDate)) {
            long daysLate = ChronoUnit.DAYS.between(expectedReturnDate, returnDate);
            BigDecimal lateFee = calculateLateFee(r.getBook().getPrice(), daysLate);
            r.setLateFee(lateFee);
            r.setStatus(Reservation.ReservationStatus.OVERDUE);
        } else {
            r.setLateFee(BigDecimal.ZERO);
            r.setStatus(Reservation.ReservationStatus.RETURNED);
        }


        bookService.increaseAvailableQuantity(r.getBook().getExternalId());

        Reservation guardada = reservationRepository.save(r);
        return convertToDTO(guardada);
    }

    @Transactional(readOnly = true)
    public ReservationResponseDTO getReservationById(Long id) {
        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Reserva no encontrada con ID: " + id));
        return convertToDTO(reservation);
    }
    
    @Transactional(readOnly = true)
    public List<ReservationResponseDTO> getAllReservations() {
        return reservationRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<ReservationResponseDTO> getReservationsByUserId(Long userId) {
        return reservationRepository.findByUserId(userId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<ReservationResponseDTO> getActiveReservations() {
        return reservationRepository.findByStatus(Reservation.ReservationStatus.ACTIVE).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<ReservationResponseDTO> getOverdueReservations() {
        return reservationRepository.findOverdueReservations().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    private BigDecimal calculateTotalFee(BigDecimal tarifDiaria, Integer diasRent) {
        if (tarifDiaria == null || diasRent == null) {
            return BigDecimal.ZERO;
        }
        return tarifDiaria
                .multiply(BigDecimal.valueOf(diasRent))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateLateFee(BigDecimal libroPrecio, long diasDesp) {
        if (libroPrecio == null || diasDesp <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal porDia = libroPrecio.multiply(LATE_FEE_PERCENTAGE);
        return porDia
                .multiply(BigDecimal.valueOf(diasDesp))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private ReservationResponseDTO convertToDTO(Reservation reservation) {
        ReservationResponseDTO dto = new ReservationResponseDTO();
        dto.setId(reservation.getId());

        if (reservation.getUser() != null) {
            dto.setUserId(reservation.getUser().getId());
            dto.setUserName(reservation.getUser().getName());
        }

        if (reservation.getBook() != null) {
            dto.setBookExternalId(reservation.getBook().getExternalId());
            dto.setBookTitle(reservation.getBook().getTitle());
        }

        dto.setRentalDays(reservation.getRentalDays());
        dto.setStartDate(reservation.getStartDate());
        dto.setExpectedReturnDate(reservation.getExpectedReturnDate());
        dto.setActualReturnDate(reservation.getActualReturnDate());
        dto.setDailyRate(reservation.getDailyRate());
        dto.setTotalFee(reservation.getTotalFee());
        dto.setLateFee(reservation.getLateFee());
        dto.setStatus(reservation.getStatus());
        dto.setCreatedAt(reservation.getCreatedAt());
        return dto;
    }
}

