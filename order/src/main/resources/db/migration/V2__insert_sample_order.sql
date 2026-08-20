INSERT INTO orders (
    id,
    order_number,
    customer_id,
    status,
    total_amount,
    currency,
    rejection_reason,
    created_at,
    updated_at
) VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'ORD-2026-000001',
    '0f52f7d1-f83b-4bbc-a1f4-ecac92cc287a',
    'CONFIRMED',
    6549.40,
    'BRL',
    NULL,
    '2026-08-19T18:30:00Z',
    '2026-08-19T18:31:12Z'
);

INSERT INTO order_items (
    id, order_id, product_id, product_name, quantity, unit_price, subtotal, position
) VALUES
    (
        '9c282be7-0f09-4c90-89ea-af3234d1f8ed',
        '550e8400-e29b-41d4-a716-446655440000',
        '6c20b55a-2e09-4473-98a6-411f48a8bb23',
        'Smart TV 55 polegadas 4K', 1, 2499.90, 2499.90, 0
    ),
    (
        '09066db0-cf7e-4411-8319-298651683a08',
        '550e8400-e29b-41d4-a716-446655440000',
        '41b22397-7c78-43c1-b587-0dc243d76af9',
        'Soundbar com subwoofer Bluetooth', 1, 899.90, 899.90, 1
    ),
    (
        'fabdf85a-0d0a-4d0d-a6a0-29fe2b6e7d36',
        '550e8400-e29b-41d4-a716-446655440000',
        '5da9c24f-70b8-4a5d-90e2-f72b4d26ec44',
        'Kit alarme residencial inteligente', 1, 329.90, 329.90, 2
    ),
    (
        '4f84ab27-71f8-42c1-b3bd-ac44a8af21c0',
        '550e8400-e29b-41d4-a716-446655440000',
        'c18d10ba-2c75-45d1-9e2d-5694e10b4f2f',
        'Smartphone 5G 256 GB', 1, 1899.90, 1899.90, 3
    ),
    (
        'd2350054-813e-422f-bb3d-7032143c1bd7',
        '550e8400-e29b-41d4-a716-446655440000',
        'aba00ff4-ed82-4462-b1ae-fd6a02a90f37',
        'Câmera de segurança Wi-Fi Full HD', 2, 459.90, 919.80, 4
    );
