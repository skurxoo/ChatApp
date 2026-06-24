import os
from PIL import Image
import sys

def make_background_transparent(input_path, output_path, color_tolerance=20):
    """
    Make the blue background of tiger.png transparent
    
    Args:
        input_path (str): Path to input tiger.png file
        output_path (str): Path to save processed image
        color_tolerance (int): Tolerance for color matching (default: 20)
    """
    
    # Check if input file exists
    if not os.path.exists(input_path):
        print(f"Error: Input file {input_path} not found")
        return False
    
    try:
        # Open the image
        img = Image.open(input_path)
        
        # Convert to RGBA if not already
        if img.mode != 'RGBA':
            img = img.convert('RGBA')
        
        # Get image data
        data = img.getdata()
        new_data = []
        
        # Define blue color (adjust as needed)
        # Default blue background is assumed to be around RGB(0, 102, 204) 
        blue_r, blue_g, blue_b = 0, 102, 204
        
        for item in data:
            r, g, b, a = item
            
            # Check if pixel is close to blue background
            if (abs(r - blue_r) < color_tolerance and 
                abs(g - blue_g) < color_tolerance and 
                abs(b - blue_b) < color_tolerance):
                new_data.append((255, 255, 255, 0))  # Make transparent
            else:
                new_data.append(item)
        
        # Update image with new data
        img.putdata(new_data)
        
        # Save the result
        img.save(output_path, 'PNG')
        print(f"Successfully processed {input_path} -> {output_path}")
        return True
        
    except Exception as e:
        print(f"Error processing image: {e}")
        return False

def main():
    # Get the current working directory
    current_dir = os.getcwd()
    
    # Define paths for tiger.png
    input_file = os.path.join(current_dir, 'server', 'game-packs', 'swords-game', 'assets', 'tiger.png')
    output_file = os.path.join(current_dir, 'server', 'game-packs', 'swords-game', 'assets', 'tiger_transparent.png')
    
    print("Processing tiger.png to make background transparent...")
    print(f"Input file: {input_file}")
    print(f"Output file: {output_file}")
    
    success = make_background_transparent(input_file, output_file)
    
    if success:
        print("\nImage processing completed successfully!")
        print("The transparent version has been saved as tiger_transparent.png")
        print("You can now replace the original tiger.png with this transparent version.")
    else:
        print("\nFailed to process image")

if __name__ == "__main__":
    main()