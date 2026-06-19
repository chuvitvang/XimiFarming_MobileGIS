-- 1. Kích hoạt tiện ích mở rộng PostGIS
create extension if not exists postgis;

-- 2. Xóa các bảng cũ nếu tồn tại (để tránh xung đột khi chạy lại)
drop table if exists public.crop_logs cascade;
drop table if exists public.crops cascade;
drop table if exists public.plots cascade;

-- 3. Tạo bảng Lô Đất (plots)
create table public.plots (
    id bigint generated always as identity primary key,
    owner_id uuid references auth.users(id) on delete cascade default auth.uid(),
    name text not null,
    description text,
    coordinates_json jsonb not null, -- Chuỗi JSON lưu trữ mảng tọa độ [[lat, lng], ...]
    geom geometry(Polygon, 4326),    -- Định dạng dữ liệu không gian PostGIS
    area_sq_meters double precision,
    health_status text check (health_status in ('GOOD', 'WARNING', 'DANGER')) default 'GOOD',
    avg_ndvi double precision default 0.0,
    created_at timestamp with time zone default timezone('utc'::text, now()) not null,
    updated_at timestamp with time zone default timezone('utc'::text, now()) not null
);

create index plots_geom_idx on public.plots using gist (geom);

-- 4. Tạo bảng Cây Trồng (crops)
create table public.crops (
    id bigint generated always as identity primary key,
    plot_id bigint references public.plots(id) on delete cascade,
    owner_id uuid references auth.users(id) on delete cascade default auth.uid(),
    name text not null,
    type text not null,
    planting_date timestamp with time zone not null,
    latitude double precision not null,
    longitude double precision not null,
    geom geometry(Point, 4326), -- Điểm vị trí của cây
    status text check (status in ('HEALTHY', 'STRESSED', 'DISEASED')) default 'HEALTHY',
    created_at timestamp with time zone default timezone('utc'::text, now()) not null,
    updated_at timestamp with time zone default timezone('utc'::text, now()) not null
);

create index crops_geom_idx on public.crops using gist (geom);
create index crops_plot_id_idx on public.crops(plot_id);

-- 5. Tạo bảng Nhật Ký Cây Trồng (crop_logs)
create table public.crop_logs (
    id bigint generated always as identity primary key,
    crop_id bigint references public.crops(id) on delete cascade,
    owner_id uuid references auth.users(id) on delete cascade default auth.uid(),
    date timestamp with time zone not null,
    status text not null,
    notes text,
    photo_url text, -- Đường dẫn tệp ảnh lưu tại Supabase Storage bucket
    created_at timestamp with time zone default timezone('utc'::text, now()) not null
);

create index crop_logs_crop_id_idx on public.crop_logs(crop_id);

-- 6. Trigger tự động đồng bộ hình học (Geometry) cho Lô Đất
create or replace function public.fn_sync_plot_geometry()
returns trigger as $$
declare
    coords_text text;
    geom_wkt text;
begin
    -- Tạo chuỗi định dạng WKT POLYGON từ coordinates_json dạng [[lat, lng], [lat, lng]...]
    -- PostGIS sử dụng định dạng: Lng (Kinh độ) rồi tới Lat (Vĩ độ)
    select 'POLYGON((' || 
           string_agg(elem->>1 || ' ' || elem->>0, ',') || 
           ',' || (new.coordinates_json->0->>1) || ' ' || (new.coordinates_json->0->>0) || -- Kết nối lại đỉnh đầu để đóng đa giác
           '))'
    into geom_wkt
    from jsonb_array_elements(new.coordinates_json) as elem;

    new.geom := ST_GeomFromText(geom_wkt, 4326);
    
    -- Tự động tính diện tích thực tế nếu chưa được gửi
    if new.area_sq_meters is null or new.area_sq_meters = 0 then
        new.area_sq_meters := ST_Area(new.geom::geography);
    end if;

    return new;
exception
    when others then
        -- Bỏ qua lỗi nếu JSON không đúng định dạng
        return new;
end;
$$ language plpgsql;

create trigger trg_sync_plot_geometry
before insert or update of coordinates_json on public.plots
for each row execute function public.fn_sync_plot_geometry();

-- 7. Trigger tự động đồng bộ điểm vị trí Point cho Cây Trồng
create or replace function public.fn_sync_crop_geometry()
returns trigger as $$
begin
    new.geom := ST_SetSRID(ST_MakePoint(new.longitude, new.latitude), 4326);
    return new;
end;
$$ language plpgsql;

create trigger trg_sync_crop_geometry
before insert or update of latitude, longitude on public.crops
for each row execute function public.fn_sync_crop_geometry();

-- 8. Kích hoạt Row Level Security (RLS) bảo mật đa người dùng
alter table public.plots enable row level security;
alter table public.crops enable row level security;
alter table public.crop_logs enable row level security;

-- Tạo chính sách RLS
create policy "Plots: owner can manage" on public.plots for all using (auth.uid() = owner_id) with check (auth.uid() = owner_id);
create policy "Crops: owner can manage" on public.crops for all using (auth.uid() = owner_id) with check (auth.uid() = owner_id);
create policy "CropLogs: owner can manage" on public.crop_logs for all using (auth.uid() = owner_id) with check (auth.uid() = owner_id);
